package io.github.zhoutaoq.shadowflow.biz.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 账户一分钟走势（需求 v1.6.7 第 ③ 条）：<b>采样 + 查询</b>。
 *
 * <p>为什么自己做采样而不是拿现成的分钟 K 线拼：本系统只接日 K（行情数据服务与新浪实时行情），
 * 没有分钟线可查。要画「账户总市值」的分钟曲线，只能在自己的口径上打点 ——
 * 而且账户市值本来就含有可用金额、当天买入均价这些本地概念，外部数据源的分钟线也拼不出来。
 *
 * <p>采样口径与页面完全一致：直接复用 {@link AccountService#summary(long, long)}，
 * 所以曲线上的每一点都等于「那一刻打开页面会看到的总市值」，不会出现「图和数字对不上」。
 *
 * <p>只在<b>真的开着市</b>的时候写点：判据是「至少一条持仓的行情时间就是今天」。
 * 周末与节假日新浪返回的是上一个交易日的快照，时间戳对不上今天就跳过 ——
 * 否则一条平线会把假期也画成交易日，看图的人分不清那天到底有没有交易。
 */
@Service
public class AccountTrendService {

    private static final Logger log = LoggerFactory.getLogger(AccountTrendService.class);

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("HH:mm");

    private static final LocalTime AM_OPEN = LocalTime.of(9, 30);
    private static final LocalTime AM_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime PM_OPEN = LocalTime.of(13, 0);
    private static final LocalTime PM_CLOSE = LocalTime.of(15, 0);

    /** 走势保留天数：一天 240 个点，留太久文件会一直涨 */
    static final int KEEP_DAYS = 45;

    private final AccountTrendRepository repository;
    private final AccountService accountService;
    private final AccountRepository accountRepository;

    /**
     * accountId → 最近一次采样到的「日期 分钟」。
     *
     * <p>调度器每 10 秒触发一次，但一分钟只需要一个点：靠它把同一分钟内的重复触发挡在
     * 「算行情」之前 —— 否则一本账一分钟要打 6 轮行情请求，白烧接口配额。
     * 进程重启后这张表清空，最多多算一轮，无害。
     */
    private final Map<Long, String> lastSampleKey = new ConcurrentHashMap<>();

    public AccountTrendService(AccountTrendRepository repository,
                               AccountService accountService,
                               AccountRepository accountRepository) {
        this.repository = repository;
        this.accountService = accountService;
        this.accountRepository = accountRepository;
    }

    /**
     * 给所有用户、所有账户各打一个当前分钟的点（由 {@link AccountTrendTask} 每分钟触发）。
     *
     * @return 实际写入的点数
     */
    public int sampleAll() {
        LocalTime now = LocalTime.now(ZONE);
        if (!inSession(now)) {
            return 0;
        }
        String today = LocalDate.now(ZONE).toString();
        String minute = now.format(MINUTE);
        String sampleKey = today + " " + minute;
        int saved = 0;
        int skipped = 0;
        for (Long userId : accountRepository.listUserIds()) {
            for (Account account : accountRepository.listAccounts(userId)) {
                if (sampleKey.equals(lastSampleKey.get(account.getId()))) {
                    continue;
                }
                try {
                    AccountSummary s = accountService.summary(userId, account.getId());
                    lastSampleKey.put(account.getId(), sampleKey);
                    if (!marketOpenToday(s)) {
                        skipped++;
                        continue;
                    }
                    repository.savePoint(userId, account.getId(), today, toPoint(minute, s));
                    saved++;
                } catch (Exception e) {
                    // 一本账算失败不影响其它账；定时任务里抛异常会让调度线程停掉
                    log.warn("[account] 账户 {} 走势采样失败：{}", account.getId(), e.getMessage());
                }
            }
        }
        if (now.getHour() == 15 && now.getMinute() <= 15) {
            purgeOld(today);
        }
        if (saved > 0 || skipped > 0) {
            log.info("[account] 走势采样 {}：写入 {} 本账，跳过 {} 本账（今天没有行情/没有持仓）",
                    minute, saved, skipped);
        }
        return saved;
    }

    /**
     * 某个账户某一天的分钟点（升序、原始采样值，缺失分钟由前端补平）。
     *
     * <p>今天是特例：如果当前分钟还没有点，顺手补一个 —— 定时任务可能刚重启过、
     * 或者用户正好在整分钟之间打开页面，补这一个点能让曲线立刻连到「现在」。
     */
    public List<AccountTrendPoint> series(long userId, long accountId, String date) {
        String day = normalizeDate(date);
        long aid = accountService.resolveAccountId(userId, accountId);
        List<AccountTrendPoint> points = new ArrayList<>(repository.list(userId, aid, day));
        if (day.equals(LocalDate.now(ZONE).toString())) {
            LocalTime now = LocalTime.now(ZONE);
            if (inSession(now)) {
                String minute = now.format(MINUTE);
                if (points.isEmpty() || !minute.equals(points.get(points.size() - 1).getMinute())) {
                    try {
                        AccountSummary s = accountService.summary(userId, aid);
                        if (marketOpenToday(s)) {
                            AccountTrendPoint p = toPoint(minute, s);
                            repository.savePoint(userId, aid, day, p);
                            points.add(p);
                        }
                    } catch (Exception e) {
                        log.warn("[account] 账户 {} 当前分钟补点失败：{}", aid, e.getMessage());
                    }
                }
            }
        }
        return points;
    }

    /** 账户被删时一并清掉走势（文件模式与数据库模式都要清，否则会留下孤儿数据） */
    public void dropAccount(long userId, long accountId) {
        try {
            repository.deleteAccount(userId, accountId);
        } catch (Exception e) {
            log.warn("[account] 账户 {} 走势清理失败：{}", accountId, e.getMessage());
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private AccountTrendPoint toPoint(String minute, AccountSummary s) {
        return new AccountTrendPoint(minute,
                nz(s.getTotalValue()), nz(s.getPositionValue()), nz(s.getDayProfit()));
    }

    /**
     * 今天到底有没有开市：拿持仓的行情时间去对今天的日期。
     *
     * <p>没有持仓的账户不采样 —— 市值恒等于可用金额，画出来是一条没有任何信息量的直线。
     */
    private static boolean marketOpenToday(AccountSummary s) {
        if (s == null || s.getPositions().isEmpty()) {
            return false;
        }
        String today = LocalDate.now(ZONE).toString();
        for (AccountSummary.PositionView v : s.getPositions()) {
            if (v.getQuoteTime() != null && v.getQuoteTime().startsWith(today)) {
                return true;
            }
        }
        return false;
    }

    /** 分钟是否落在交易时段内（09:30–11:30、13:00–15:00，闭区间） */
    static boolean inSession(LocalTime t) {
        boolean am = !t.isBefore(AM_OPEN) && !t.isAfter(AM_CLOSE);
        boolean pm = !t.isBefore(PM_OPEN) && !t.isAfter(PM_CLOSE);
        return am || pm;
    }

    /**
     * 日期口径：空 = 今天；{@code yyyy-MM-dd} / {@code yyyyMMdd} 都认；
     * 未来日期不认（还没有走势可看），直接报错让用户改。
     *
     * <p>public 是给控制层回显用的：页面要显示「你看的是哪一天」，不能只靠自己猜。
     */
    public static String normalizeDate(String raw) {
        String today = LocalDate.now(ZONE).toString();
        if (raw == null || raw.trim().isEmpty()) {
            return today;
        }
        String ymd = AccountService.checkYmd(raw, "查询日期");
        if (ymd == null) {
            return today;
        }
        if (ymd.compareTo(today) > 0) {
            throw new IllegalArgumentException("还不能看未来日期的走势，请选今天或更早");
        }
        return ymd;
    }

    /** 只保留最近 {@link #KEEP_DAYS} 天 */
    private void purgeOld(String today) {
        try {
            String keepFrom = LocalDate.parse(today).minusDays(KEEP_DAYS).toString();
            int removed = repository.purgeBefore(keepFrom);
            if (removed > 0) {
                log.info("[account] 走势清理完成：删掉 {} 之前（{} 天前）的 {} 个点", keepFrom, KEEP_DAYS, removed);
            }
        } catch (Exception e) {
            log.warn("[account] 走势清理失败：{}", e.getMessage());
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
