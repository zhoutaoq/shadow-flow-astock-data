package io.github.zhoutaoq.shadowflow.biz.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * feat3 推送（三）：在工作日的关键时点对全部盯盘股票做一次涨跌幅快照，合并成一条钉钉消息推送。
 *
 * <p>触发时点（北京时间 HH:mm:ss）：
 * <pre>09:15:15  09:20:15  09:25:05  09:30:15  11:30:15  13:00:15  15:00:15</pre>
 *
 * <p>与 {@link PriceWatchTask} 的"超阈值才告警"不同，这里是固定时点的例行播报：每到上述时点，
 * 用行情客户端并发查一遍所有盯盘标的，取现价与涨跌幅，逐只一行、整体一条推送。周末不排任务；
 * 机器人未配置、盯盘未开启、名单为空时自动跳过。
 *
 * <p><b>v1.5.2</b>：播报按用户各发各的 —— 每个运行中的用户一份快报，进自己配的群。
 * 用户之间互相看不到对方的名单，A 的时点播报不会因为 B 把开关关了而消失。
 *
 * <p>调度用独立单线程守护池，每跑完一次就按「距下一个时点」重新排一次单次任务，跨时点不叠加；
 * 进程任意时刻启动都会先算下一次触发。
 */
@Component
@ConditionalOnProperty(name = "watch.enabled", havingValue = "true", matchIfMissing = true)
public class WatchKeyTimePusher implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(WatchKeyTimePusher.class);

    /** 每日关键时点（秒数从当日 00:00 起），升序 */
    private static final int[] TIMES_SECONDS = {
            timeToSec(9, 15, 15), timeToSec(9, 20, 15), timeToSec(9, 25, 5),
            timeToSec(9, 30, 15), timeToSec(11, 30, 15), timeToSec(13, 0, 15),
            timeToSec(15, 0, 15)
    };

    /** 单只行情查询的超时上限（秒） */
    private static final int QUOTE_TIMEOUT_SEC = 10;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final WatchProperties props;
    private final WatchConfigStore store;
    private final SinaQuoteClient quoteClient;
    private final DingTalkNotifier notifier;
    private final ExecutorService quotePool;
    private final ScheduledExecutorService scheduler;

    public WatchKeyTimePusher(WatchProperties props, WatchConfigStore store,
                              SinaQuoteClient quoteClient, DingTalkNotifier notifier,
                              @Qualifier("watchExecutor") ExecutorService watchExecutor) {
        this.props = props;
        this.store = store;
        this.quoteClient = quoteClient;
        this.notifier = notifier;
        this.quotePool = watchExecutor;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "watch-clock-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    @PostConstruct
    public void start() {
        reschedule();
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /** 推到下一个工作日关键时点执行一次；跑完会再排下一个，链式不叠加 */
    private void reschedule() {
        long delayMs = millisUntilNext(LocalDateTime.now());
        scheduler.schedule(this, delayMs, TimeUnit.MILLISECONDS);
        log.info("[watch] 关键时点播报已排程：{} ms 后触发", delayMs);
    }

    @Override
    public void run() {
        try {
            for (long userId : store.activeUserIds()) {
                try {
                    pushForUser(userId);
                } catch (Exception e) {
                    // 单个用户失败不能拖住其余用户的播报
                    log.warn("[watch] 用户 {} 的关键时点播报异常: {}", userId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[watch] 关键时点播报异常: {}", e.getMessage());
        } finally {
            // 无论成功失败都续排下一次，避免一次失败把整条链打断
            reschedule();
        }
    }

    /** 这个用户此刻要不要播报：开着盯盘、有机器人可用、名单非空 */
    private boolean shouldPush(long userId) {
        return !store.webhookOf(userId).isEmpty()
                && store.enabledOf(userId)
                && !store.targetsOf(userId).isEmpty();
    }

    /** 抓某个用户盯盘标的的现价，合并成一条涨跌幅快报推到他的群 */
    private void pushForUser(long userId) {
        if (!shouldPush(userId)) {
            return;
        }
        List<WatchTarget> targets = store.targetsOf(userId);
        List<Quote> quotes = fetchAll(targets);

        List<String> validLines = new ArrayList<>();
        int up = 0, down = 0, flat = 0;
        for (Quote q : quotes) {
            if (q.getPrice() == null || q.getPrice().signum() <= 0) {
                continue; // 未开盘/停牌现价为 0，跳过，不计入统计
            }
            validLines.add(line(q));
            BigDecimal pct = q.changePercent();
            if (pct == null) {
                continue;
            }
            int s = pct.signum();
            if (s > 0) up++;
            else if (s < 0) down++;
            else flat++;
        }
        if (validLines.isEmpty()) {
            log.info("[watch] 用户 {} 关键时点 {} 无有效行情，跳过推送",
                    userId, LocalDateTime.now().format(FMT));
            return; // 名单虽有但一个有效行情都没有，不发空消息
        }
        StringBuilder sb = new StringBuilder("盯盘涨跌幅快报 ")
                .append(LocalDateTime.now().format(FMT)).append('\n');
        for (String line : validLines) {
            sb.append(line).append('\n');
        }
        sb.append("合计 ").append(validLines.size()).append(" 只")
                .append(" ｜ 上涨 ").append(up)
                .append(" ｜ 下跌 ").append(down)
                .append(" ｜ 平盘 ").append(flat);
        String content = sb.toString();
        if (notifier.sendTo(store.webhookOf(userId), content)) {
            log.info("[watch] 用户 {} 关键时点已推送 {} 只涨跌幅快报", userId, validLines.size());
        } else {
            log.warn("[watch] 钉钉未推送（未配置机器人或调用失败），用户 {}，关键时点内容：{}", userId, content);
        }
    }

    private List<Quote> fetchAll(List<WatchTarget> targets) {
        List<Future<Quote>> futures = new ArrayList<>(targets.size());
        for (WatchTarget t : targets) {
            futures.add(quotePool.submit(() -> quoteClient.fetch(t)));
        }
        List<Quote> out = new ArrayList<>(targets.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                Quote q = futures.get(i).get(QUOTE_TIMEOUT_SEC, TimeUnit.SECONDS);
                if (q != null) {
                    out.add(q);
                }
            } catch (Exception e) {
                log.warn("[watch] 关键时点查价失败 {} {}", targets.get(i), e.getMessage());
            }
        }
        return out;
    }

    private static String line(Quote q) {
        return q.getName() + "(" + q.getCode() + ") 现价 " + WatchText.price(q.getPrice())
                + " 涨跌幅 " + WatchText.signed(q.changePercent());
    }

    /** 距下一个工作日关键时点的毫秒数（严格晚于 now；往后最多找 8 天必然命中一个工作日） */
    private static long millisUntilNext(LocalDateTime now) {
        for (int day = 0; day < 8; day++) {
            LocalDate date = now.toLocalDate().plusDays(day);
            DayOfWeek w = date.getDayOfWeek();
            if (w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY) {
                continue;
            }
            for (int secOfDay : TIMES_SECONDS) {
                LocalDateTime candidate = LocalDateTime.of(date, LocalTime.ofSecondOfDay(secOfDay));
                if (candidate.isAfter(now)) {
                    return Duration.between(now, candidate).toMillis();
                }
            }
        }
        // 理论走不到；保底顺延一天再找
        return Duration.between(now, LocalDateTime.of(now.toLocalDate().plusDays(1),
                LocalTime.ofSecondOfDay(TIMES_SECONDS[0]))).toMillis();
    }

    private static int timeToSec(int h, int m, int s) {
        return h * 3600 + m * 60 + s;
    }
}
