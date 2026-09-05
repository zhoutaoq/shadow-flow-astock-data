package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 盯盘主流程：定时调度 → 线程池并发取现价 → 与上一次查询的价格比对 → 超阈值推钉钉。
 *
 * <p>状态只放在内存里（code → 上次价格 / 上次时间），不落库；进程重启后重新建立基线，
 * 所以第一轮只记录、不推送。
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

    private final WatchProperties props;
    private final WatchListLoader loader;
    private final SinaQuoteClient quoteClient;
    private final DingTalkNotifier notifier;
    private final ExecutorService executor;

    /** code → 上一次成功查询的价格与时刻 */
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public PriceWatchTask(WatchProperties props,
                          WatchListLoader loader,
                          SinaQuoteClient quoteClient,
                          DingTalkNotifier notifier,
                          @Qualifier("watchExecutor") ExecutorService watchExecutor) {
        this.props = props;
        this.loader = loader;
        this.quoteClient = quoteClient;
        this.notifier = notifier;
        this.executor = watchExecutor;
    }

    /** 上一次查询的快照 */
    private static class State {
        private BigDecimal price;
        private long timeMs;
        /** 上次的封板状态：1 涨停 / 0 未涨跌停 / -1 跌停 */
        private int limitState = LIMIT_NONE;
    }

    /**
     * 一轮轮询。间隔由 <code>watch.interval-seconds</code> 决定，用 fixedDelay 保证
     * 上一轮跑完才开始计时，慢的时候不会叠加。
     */
    @Scheduled(
            initialDelayString = "#{${watch.interval-seconds:30} * 1000}",
            fixedDelayString = "#{${watch.interval-seconds:30} * 1000}")
    public void runOnce() {
        List<WatchTarget> targets;
        try {
            targets = loader.load();
        } catch (Exception e) {
            log.warn("[watch] 读取盯盘名单异常: {}", e.getMessage());
            return;
        }
        if (targets.isEmpty()) {
            return;
        }

        long begin = System.currentTimeMillis();
        boolean open = isTradingTime();

        // 未开盘就不调接口；但首次（该标的还没有基线）必须调一次，否则永远建不出基线。
        // queried 与 futures 一一对应，跳过的不进列表，避免下标错位。
        List<Future<Quote>> futures = new ArrayList<>(targets.size());
        List<WatchTarget> queried = new ArrayList<>(targets.size());
        for (WatchTarget t : targets) {
            if (!open && states.containsKey(t.getCode())) {
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
                q = futures.get(i).get(Math.max(1, props.getIntervalSeconds()), TimeUnit.SECONDS);
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
     * 与上次价格比对并决定是否推送。
     *
     * @return 是否触发了推送
     */
    private boolean check(WatchTarget t, Quote q) {
        State prev = states.get(q.getCode());
        int limitState = limitStateOf(q);

        State cur = new State();
        cur.price = q.getPrice();
        cur.timeMs = q.getQuoteTime();
        cur.limitState = limitState;
        states.put(q.getCode(), cur);

        if (prev == null) {
            log.info("[watch] {} 建立基线：现价 {}（首轮不推送）", q.getName(), q.getPrice());
            return false;
        }

        boolean pushed = false;

        // 封板 / 开板通知：状态变了才推，连续封着或一直没封都不重复刷屏
        if (limitState != prev.limitState) {
            // 开板：上一轮还封着，这一轮没封（或封到反向去了）
            if (prev.limitState == LIMIT_UP) {
                sendLimitAlert(q, LIMIT_UP, false);
                pushed = true;
            } else if (prev.limitState == LIMIT_DOWN) {
                sendLimitAlert(q, LIMIT_DOWN, false);
                pushed = true;
            }
            // 新封板
            if (limitState == LIMIT_UP || limitState == LIMIT_DOWN) {
                sendLimitAlert(q, limitState, true);
                pushed = true;
            }
        }

        long seconds = Math.max(1, (q.getQuoteTime() - prev.timeMs) / 1000);
        BigDecimal delta = q.getPrice().subtract(prev.price)
                .multiply(HUNDRED)
                .divide(prev.price, 2, BigDecimal.ROUND_HALF_UP);
        if (delta.abs().compareTo(BigDecimal.valueOf(props.getThresholdPercent())) >= 0) {
            String msg = format(q.getName(), seconds, delta, q.getPrice(), q.changePercent());
            if (notifier.send(msg)) {
                log.info("[watch] 已推送：{}", msg);
            } else {
                // webhook 没配或推送失败：把内容打进日志，保证不丢信息
                log.warn("[watch] 钉钉未推送（webhook 未配置或调用失败），内容：{}", msg);
            }
            pushed = true;
        }
        return pushed;
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

    /**
     * 拼消息正文，格式固定为：
     * <pre>xxx 在近 x s 内涨/跌幅达到 xx%，现价 x 元，涨跌幅为 x。</pre>
     */
    static String format(String name, long seconds, BigDecimal delta,
                         BigDecimal price, BigDecimal changePercent) {
        String dir = delta.signum() >= 0 ? "涨" : "跌";
        String pctText = changePercent == null ? "—" : changePercent.toPlainString() + "%";
        return name + " 在近 " + seconds + " s 内" + dir + "幅达到 "
                + delta.abs().toPlainString() + "%，现价 " + price.toPlainString()
                + " 元，涨跌幅为 " + pctText + "。";
    }
}
