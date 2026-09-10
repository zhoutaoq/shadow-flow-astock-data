package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 盯盘主流程：定时调度 → 线程池并发取现价 → 与历史价格比对 → 多规则任一触发推钉钉。
 *
 * <p>v1.2 改造：
 * <ul>
 *   <li>每个标的的 State 改为按时间保存价格采样（覆盖所有规则的"过去 N 秒"），不再只记上一次</li>
 *   <li>告警条件变成多规则（过去时长 + 偏离%），任一条命中即触发</li>
 *   <li>同一轮里多条规则同时触发合并为一条推送，列出所有触发条件</li>
 *   <li>推送内容含拉升成交量、成交额，并按涨幅分档措辞（上涨 / 快速上涨 / 急速拉升）</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "watch.enabled", havingValue = "true", matchIfMissing = true)
public class PriceWatchTask {

    private static final Logger log = LoggerFactory.getLogger(PriceWatchTask.class);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 封板状态：涨停 / 未涨跌停 / 跌停 */
    private static final int LIMIT_UP = 1;
    private static final int LIMIT_NONE = 0;
    private static final int LIMIT_DOWN = -1;

    /** 封板判断阈值（百分比，各留 0.5% 容错）：ST 5%→4.5%，创业 30x / 科创 688 为 20%→19.5%，其余 10%→9.5% */
    private static final BigDecimal TH_ST = new BigDecimal("4.5");
    private static final BigDecimal TH_GROW = new BigDecimal("19.5");
    private static final BigDecimal TH_MAIN = new BigDecimal("9.5");

    private final WatchConfigStore store;
    private final SinaQuoteClient quoteClient;
    private final DingTalkNotifier notifier;
    private final ExecutorService executor;

    /** code → 历史价格采样（按时间升序） */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public PriceWatchTask(WatchConfigStore store,
                          SinaQuoteClient quoteClient,
                          DingTalkNotifier notifier,
                          @Qualifier("watchExecutor") ExecutorService watchExecutor) {
        this.store = store;
        this.quoteClient = quoteClient;
        this.notifier = notifier;
        this.executor = watchExecutor;
    }

    /** 页面改了名单后清掉已移除标的的状态，避免重新加回来时还留着很旧的基线 */
    public synchronized void pruneStates(List<WatchTarget> targets) {
        Set<String> keep = new HashSet<>();
        for (WatchTarget t : targets) {
            keep.add(t.getCode());
        }
        states.keySet().removeIf(code -> !keep.contains(code));
    }

    /**
     * 一个标的的运行时状态。
     *
     * <p>采样按时间升序存放，每条 {毫秒时间戳, 价格}。轮询拿到新价就 append，
     * 超过最长规则时长的旧采样 trim 掉。成交额、成交量的上一次值也保留，
     * 用来在告警消息里算"拉升成交量/成交额"。
     */
    private static class State {
        /** (时间, 价格) 历史采样 */
        final List<Sample> history = new ArrayList<>();
        /** 上一次记录里的累计成交量/成交额，用来算差值 */
        BigDecimal lastVolume;
        BigDecimal lastAmount;
        /** 上次的封板状态：1 涨停 / 0 未涨跌停 / -1 跌停 */
        int limitState = LIMIT_NONE;
    }

    private static class Sample {
        final long timeMs;
        final BigDecimal price;

        Sample(long timeMs, BigDecimal price) {
            this.timeMs = timeMs;
            this.price = price;
        }
    }

    /**
     * 一轮轮询：名单 / 规则 / 钉钉都取自 {@link WatchConfigStore}，调度由 {@link WatchScheduler} 负责。
     */
    public void runOnce() {
        List<WatchTarget> targets = store.targets();
        pruneStates(targets);
        if (targets.isEmpty()) {
            return;
        }

        long begin = System.currentTimeMillis();
        boolean open = isTradingTime();

        // 未开盘就不调接口；但首次（该标的还没有基线）必须调一次，否则永远建不出基线。
        List<Future<Quote>> futures = new ArrayList<>(targets.size());
        List<WatchTarget> queried = new ArrayList<>(targets.size());
        for (WatchTarget t : targets) {
            State st = states.get(t.getCode());
            if (!open && st != null && !st.history.isEmpty()) {
                continue;
            }
            queried.add(t);
            futures.add(submit(t));
        }
        if (queried.isEmpty()) {
            log.debug("[watch] 非交易时段，本轮跳过（{} 只均已有基线）", targets.size());
            return;
        }

        int hit = 0;
        for (int i = 0; i < futures.size(); i++) {
            WatchTarget t = queried.get(i);
            Quote q;
            try {
                q = futures.get(i).get(Math.max(1, store.getIntervalSeconds()), TimeUnit.SECONDS);
            } catch (Exception e) {
                log.warn("[watch] 查询超时或失败 {} {}", t, e.getMessage());
                continue;
            }
            if (q == null) {
                continue;
            }
            if (check(t, q)) {
                hit++;
            }
        }
        log.info("[watch] 本轮 {} 只，实际查询 {} 只，触发 {} 条，耗时 {} ms",
                targets.size(), queried.size(), hit, System.currentTimeMillis() - begin);
    }

    /** A股交易时段：周一至周五 9:30-11:30 / 13:00-15:00（法定节假日不判断） */
    private boolean isTradingTime() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getDayOfWeek().getValue() > 5) {
            return false;
        }
        int hhmm = now.getHour() * 100 + now.getMinute();
        return (hhmm >= 930 && hhmm <= 1130) || (hhmm >= 1300 && hhmm <= 1500);
    }

    private Future<Quote> submit(WatchTarget t) {
        return executor.submit(() -> quoteClient.fetch(t));
    }

    /**
     * 与历史价格比对并决定是否推送。
     *
     * @return 是否触发了推送
     */
    private boolean check(WatchTarget t, Quote q) {
        State st = states.computeIfAbsent(q.getCode(), k -> new State());
        int limitState = limitStateOf(q);

        // 写新采样 + trim 旧采样（保留所有规则都够用的窗口）
        long maxWindowSec = maxRuleSeconds();
        long cutoff = q.getQuoteTime() - maxWindowSec * 1000L;
        st.history.add(new Sample(q.getQuoteTime(), q.getPrice()));
        // 采样按时间升序；trim 头部比 trim 全表扫描更便宜
        int drop = 0;
        while (drop < st.history.size() && st.history.get(drop).timeMs < cutoff) {
            drop++;
        }
        if (drop > 0) {
            st.history.subList(0, drop).clear();
        }

        boolean pushed = false;

        // 封板 / 开板通知：状态变了才推，连续封着或一直没封都不重复刷屏
        if (limitState != st.limitState) {
            int prev = st.limitState;
            if (prev == LIMIT_UP) {
                sendLimitAlert(q, LIMIT_UP, false);
                pushed = true;
            } else if (prev == LIMIT_DOWN) {
                sendLimitAlert(q, LIMIT_DOWN, false);
                pushed = true;
            }
            if (limitState == LIMIT_UP || limitState == LIMIT_DOWN) {
                sendLimitAlert(q, limitState, true);
                pushed = true;
            }
            st.limitState = limitState;
        }

        // 首轮不推送：让采样历史先有 1 个点，下一轮才能算偏离
        if (st.history.size() < 2) {
            st.lastVolume = q.getVolume();
            st.lastAmount = q.getAmount();
            log.info("[watch] {} 建立基线：现价 {}（首轮不推送）", q.getName(), q.getPrice());
            return false;
        }

        // 多规则扫描：每条规则的"过去时长"= 该规则 intervals 倍 × 轮询间隔秒数
        List<WatchSettings.Rule> rules = store.rules();
        long intervalSec = store.getIntervalSeconds();
        List<Hit> hits = new ArrayList<>();
        for (WatchSettings.Rule r : rules) {
            long spanMs = (long) r.getIntervals() * intervalSec * 1000L;
            Sample base = findSampleBefore(st.history, q.getQuoteTime() - spanMs);
            if (base == null) {
                // 历史还不够长（刚加进来的标的还在攒采样），跳过这条规则
                continue;
            }
            BigDecimal delta = q.getPrice().subtract(base.price)
                    .multiply(HUNDRED)
                    .divide(base.price, 2, BigDecimal.ROUND_HALF_UP);
            if (delta.abs().compareTo(BigDecimal.valueOf(r.getPercent())) >= 0) {
                hits.add(new Hit(r, base, delta));
            }
        }

        if (!hits.isEmpty()) {
            String msg = buildAlert(q, hits);
            if (notifier.send(msg)) {
                log.info("[watch] 已推送：{}", msg);
            } else {
                log.warn("[watch] 钉钉未推送（webhook 未配置或调用失败），内容：{}", msg);
            }
            pushed = true;
        }

        st.lastVolume = q.getVolume();
        st.lastAmount = q.getAmount();
        return pushed;
    }

    /** 找出时间戳 <= cutoff 的最近一次采样；没找到返回 null */
    private static Sample findSampleBefore(List<Sample> history, long cutoffMs) {
        Sample hit = null;
        for (Sample s : history) {
            if (s.timeMs > cutoffMs) {
                break;
            }
            hit = s;
        }
        return hit;
    }

    /** 当前所有规则里最长的过去时长，按秒计（= maxIntervals × 当前轮询间隔秒） */
    private long maxRuleSeconds() {
        int max = 0;
        for (WatchSettings.Rule r : store.rules()) {
            if (r.getIntervals() > max) {
                max = r.getIntervals();
            }
        }
        if (max <= 0) {
            max = 1;
        }
        return (long) max * store.getIntervalSeconds();
    }

    /** 一条规则命中：保留规则 + 命中的基准采样 + 计算出的偏离 */
    private static class Hit {
        final WatchSettings.Rule rule;
        final Sample base;
        final BigDecimal delta;

        Hit(WatchSettings.Rule rule, Sample base, BigDecimal delta) {
            this.rule = rule;
            this.base = base;
            this.delta = delta;
        }
    }

    /**
     * 拼钉钉消息：把同一轮里所有命中的规则合并成一条，按涨幅取最大的一档作为主措辞。
     *
     * <p>措辞分档（绝对值，涨 / 跌对称）：
     * <ul>
     *   <li>&lt; 1.5%  上涨 / 下跌</li>
     *   <li>1.5% ~ 3% 短线快速拉升 / 短线快速下挫</li>
     *   <li>≥ 3%   急速拉升 / 急速下挫</li>
     * </ul>
     * 消息含"过去 Ns 内累计涨幅"与"当前涨跌幅"，并列出拉升成交量 / 拉升成交额。
     */
    String buildAlert(Quote q, List<Hit> hits) {
        BigDecimal maxDelta = BigDecimal.ZERO;
        for (Hit h : hits) {
            if (h.delta.abs().compareTo(maxDelta.abs()) > 0) {
                maxDelta = h.delta;
            }
        }
        boolean up = maxDelta.signum() >= 0;
        BigDecimal absPct = maxDelta.abs();
        String action = phraseFor(absPct, up);

        BigDecimal volDelta = volumeDelta(q);
        BigDecimal amtDelta = amountDelta(q);

        StringBuilder sb = new StringBuilder();
        sb.append(q.getName()).append(' ').append(action);
        // 命中的第一条规则 intervals × 轮询间隔秒 = 这次推送的主要时间范围
        long intervalSec = store.getIntervalSeconds();
        long mainSec = (long) hits.get(0).rule.getIntervals() * intervalSec;
        sb.append("，过去 ").append(mainSec).append("s 内");
        sb.append(up ? "累计涨幅 " : "累计跌幅 ")
                .append(absPct.toPlainString()).append('%');
        sb.append("，现价 ").append(q.getPrice().toPlainString()).append(" 元");
        BigDecimal todayPct = q.changePercent();
        sb.append("，当前涨跌幅 ")
                .append(todayPct == null ? "—" : todayPct.toPlainString()).append('%');
        if (volDelta != null) {
            sb.append("，拉升成交量：").append(formatVolume(volDelta));
        }
        if (amtDelta != null) {
            sb.append("，拉升成交额：").append(formatAmount(amtDelta));
        }
        sb.append('。');
        return sb.toString();
    }

    /**
     * 措辞分档：阈值跟用户口头说的口径对齐（<1.5 / 2-3 / >3）。
     * 1.5~2% 区间并入"快速"档；其余按原口径。
     */
    static String phraseFor(BigDecimal absPct, boolean up) {
        // absPct 一定 ≥ 0；按口径分档
        if (up) {
            if (absPct.compareTo(new BigDecimal("1.5")) < 0) return "上涨了";
            if (absPct.compareTo(new BigDecimal("3")) < 0) return "短线快速拉升";
            return "急速拉升";
        } else {
            if (absPct.compareTo(new BigDecimal("1.5")) < 0) return "下跌了";
            if (absPct.compareTo(new BigDecimal("3")) < 0) return "短线快速下挫";
            return "急速下挫";
        }
    }

    /** 拉升成交量 = 上一轮累计成交量到本轮累计成交量的差值（可能为负：缩量就显示 0） */
    private BigDecimal volumeDelta(Quote q) {
        if (q.getVolume() == null) {
            return null;
        }
        State st = states.get(q.getCode());
        if (st == null || st.lastVolume == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = q.getVolume().subtract(st.lastVolume);
        return diff.signum() < 0 ? BigDecimal.ZERO : diff;
    }

    /** 拉升成交额：同上 */
    private BigDecimal amountDelta(Quote q) {
        if (q.getAmount() == null) {
            return null;
        }
        State st = states.get(q.getCode());
        if (st == null || st.lastAmount == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal diff = q.getAmount().subtract(st.lastAmount);
        return diff.signum() < 0 ? BigDecimal.ZERO : diff;
    }

    /** 成交量按"手"显示（v 单位是"股"，1手=100股）：1234 → 1234 手；超过 1 亿手 → 1.23 亿手 */
    static String formatVolume(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal hand = v.divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
        if (hand.compareTo(BigDecimal.valueOf(100000000)) >= 0) {
            return hand.divide(BigDecimal.valueOf(100000000), 2, BigDecimal.ROUND_HALF_UP).toPlainString() + " 亿手";
        }
        return hand.toPlainString() + " 手";
    }

    /** 成交额按"万元"显示 */
    static String formatAmount(BigDecimal v) {
        if (v == null) return "—";
        // v 单位是"元"
        BigDecimal wan = v.divide(BigDecimal.valueOf(10000L), 2, BigDecimal.ROUND_HALF_UP);
        if (wan.compareTo(BigDecimal.valueOf(10000)) >= 0) {
            return wan.divide(BigDecimal.valueOf(10000), 2, BigDecimal.ROUND_HALF_UP).toPlainString() + " 亿元";
        }
        return wan.toPlainString() + " 万元";
    }

    // ============ 涨跌停 ============

    /** 封板阈值（百分比）：名称含 ST 是 5%，创业板 30x 与科创板 688 是 20%，其余主板 10% */
    private BigDecimal limitThreshold(Quote q) {
        String name = q.getName();
        if (name != null && name.toUpperCase().contains("ST")) {
            return TH_ST;
        }
        String c = q.getCode() == null ? "" : q.getCode().trim();
        if (c.startsWith("688") || c.startsWith("30")) {
            return TH_GROW;
        }
        return TH_MAIN;
    }

    /**
     * 判断当前是否封板：现价贴在当日最高价上且涨幅达标 = 涨停；贴在最低价上且跌幅达标 = 跌停。
     *
     * @return 1 涨停 / 0 未涨跌停 / -1 跌停
     */
    private int limitStateOf(Quote q) {
        BigDecimal price = q.getPrice();
        BigDecimal pct = q.changePercent();
        if (price == null || pct == null) {
            return LIMIT_NONE;
        }
        BigDecimal th = limitThreshold(q);
        BigDecimal high = q.getHigh();
        BigDecimal low = q.getLow();
        if (high != null && price.compareTo(high) == 0 && pct.compareTo(th) > 0) {
            return LIMIT_UP;
        }
        if (low != null && price.compareTo(low) == 0 && pct.compareTo(th.negate()) < 0) {
            return LIMIT_DOWN;
        }
        return LIMIT_NONE;
    }

    /**
     * 推送封板 / 开板通知。
     *
     * @param state  涨停还是跌停
     * @param closed true = 刚封上板，false = 刚打开板
     */
    private void sendLimitAlert(Quote q, int state, boolean closed) {
        String word;
        if (state == LIMIT_UP) {
            word = closed ? "涨停" : "打开涨停";
        } else {
            word = closed ? "跌停" : "打开跌停";
        }
        BigDecimal pct = q.changePercent();
        String pctText = pct == null ? "—" : pct.toPlainString() + "%";
        String msg = q.getName() + " " + word
                + "，现价 " + q.getPrice().toPlainString() + " 元，涨跌幅为 " + pctText + "。";
        if (notifier.send(msg)) {
            log.info("[watch] 已推送：{}", msg);
        } else {
            log.warn("[watch] 钉钉未推送（webhook 未配置或调用失败），内容：{}", msg);
        }
    }
}
