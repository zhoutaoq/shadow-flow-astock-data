package io.github.zhoutaoq.shadowflow.biz.quant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 量化调度：启动初始化、0 点刷新、盘中决策、收盘结算（需求第 4、5、8 条）。
 *
 * <p><b>为什么是 30 秒固定节拍，而不是几个 cron</b>：时间条件都写在 {@link #tick()} 里，
 * 一个线程就能同时表达「交易时段才跑」「跨天才刷新」「收盘后结算一次」，
 * 不用维护多个 ScheduledFuture 的先后关系，重启后也不会有「错过 0 点就永不刷新」的问题 ——
 * 任何时刻启动，只要发现「今天还没初始化过」就补一次。
 *
 * <p><b>0 点刷新的对象</b>（需求第 5 条）：标的与参数。页面保存只写 {@code pendingJson}，
 * 这里消费掉并清空。这样盘中改参数不会影响当天正在跑的决策。
 */
@Component
@ConditionalOnProperty(name = "quant.enabled", havingValue = "true", matchIfMissing = true)
public class QuantTask {

    private static final Logger log = LoggerFactory.getLogger(QuantTask.class);

    /** 节拍：30 秒。决策本身另有策略级间隔，这里只保证「到点了能被叫醒」 */
    private static final long TICK_SECONDS = 30L;
    /** 收盘后多久结算：15:05 足够拿到收盘价，又不至于卡在收盘瞬间的最后一笔 */
    private static final LocalTime SETTLE_AFTER = LocalTime.of(15, 5);

    private final QuantRepository repository;
    private final QuantUniverseService universe;
    private final QuantDecisionService decision;
    private final QuantSnapshotService snapshot;
    private final ObjectMapper mapper = new ObjectMapper();

    private final ScheduledExecutorService scheduler;

    /** 已完成初始化 / 结算的日期，用来保证一天只做一次 */
    private volatile String initedDay = "";
    private volatile String settledDay = "";
    private volatile int lastMinuteKey = -1;

    public QuantTask(QuantRepository repository, QuantUniverseService universe,
                     QuantDecisionService decision, QuantSnapshotService snapshot) {
        this.repository = repository;
        this.universe = universe;
        this.decision = decision;
        this.snapshot = snapshot;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "quant-tick-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    @PostConstruct
    public void start() {
        // 需求第 8 条：系统启动时自动初始化一次标的
        try {
            initToday();
        } catch (RuntimeException e) {
            log.warn("[quant] 启动初始化失败（下一拍会重试）：{}", e.getMessage());
        }
        scheduler.scheduleWithFixedDelay(this::tick, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
        log.info("[quant] 调度已启动，节拍 {} 秒", TICK_SECONDS);
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }

    /* ==================== 节拍 ==================== */

    private void tick() {
        try {
            LocalDateTime now = LocalDateTime.now();
            String day = now.toLocalDate().toString();

            // 跨天：0 点后第一次跑到就刷新（不卡死在 00:00:00 那一刻）
            if (!day.equals(initedDay)) {
                initToday();
            }
            // 收盘结算：15:05 之后且当天还没结过
            if (!now.toLocalTime().isBefore(SETTLE_AFTER) && !day.equals(settledDay)) {
                settleToday();
            }
            // 盘中决策
            if (QuantDecisionService.inTradingHours(now.toLocalTime())) {
                decision.runOnce();
                int minuteKey = now.getHour() * 100 + now.getMinute();
                if (minuteKey != lastMinuteKey) {
                    lastMinuteKey = minuteKey;
                    snapshot.captureIntraday();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[quant] 节拍异常：{}", e.getMessage());
        }
    }

    /* ==================== 初始化 ==================== */

    /**
     * 初始化当日：消费待生效变更 → 重算标的池 → 放开 T+1 可用数量。
     *
     * <p>顺序不能反：标的池依赖合并后的 watchCodes，可用数量依赖持仓。
     */
    public synchronized void initToday() {
        String day = LocalDate.now().toString();
        int merged = 0;
        for (Long userId : repository.listStrategyUserIds()) {
            for (QuantStrategy s : repository.listStrategies(userId)) {
                if (mergePending(s)) {
                    merged++;
                }
            }
        }
        universe.refresh();
        int reset = resetAvailableQty();
        initedDay = day;
        log.info("[quant] {} 初始化完成：合并待生效 {} 个策略，放开 T+1 可用数量 {} 条", day, merged, reset);
    }

    /** 把 pendingJson 合并进生效字段并清空；返回是否真的合并了 */
    private boolean mergePending(QuantStrategy s) {
        String json = s.getPendingJson();
        if (json == null || json.trim().isEmpty()) {
            return false;
        }
        try {
            JsonNode n = mapper.readTree(json);
            if (n.hasNonNull("codes")) {
                s.setWatchCodes(n.get("codes").asText(""));
            }
            JsonNode p = n.path("params");
            if (p.isObject()) {
                if (p.hasNonNull("minScore")) {
                    s.setMinScore(p.get("minScore").decimalValue());
                }
                if (p.hasNonNull("maxHoldings")) {
                    // 需求第 9 条：最多 5 只，这里是硬顶，参数只能往下调
                    s.setMaxHoldings(Math.min(5, Math.max(1, p.get("maxHoldings").asInt(5))));
                }
                if (p.hasNonNull("maxPositionPct")) {
                    s.setMaxPositionPct(p.get("maxPositionPct").decimalValue());
                }
                if (p.hasNonNull("stopLossPct")) {
                    s.setStopLossPct(p.get("stopLossPct").decimalValue());
                }
                if (p.hasNonNull("takeProfitPct")) {
                    s.setTakeProfitPct(p.get("takeProfitPct").decimalValue());
                }
                if (p.hasNonNull("trailDrawdownPct")) {
                    s.setTrailDrawdownPct(p.get("trailDrawdownPct").decimalValue());
                }
                if (p.hasNonNull("minVolumeRatio")) {
                    s.setMinVolumeRatio(p.get("minVolumeRatio").decimalValue());
                }
                if (p.hasNonNull("minMainInflow")) {
                    s.setMinMainInflow(p.get("minMainInflow").decimalValue());
                }
                if (p.hasNonNull("minMainInflow5d")) {
                    s.setMinMainInflow5d(p.get("minMainInflow5d").decimalValue());
                }
                if (p.hasNonNull("requireMacdGolden")) {
                    s.setRequireMacdGolden(p.get("requireMacdGolden").asBoolean(false));
                }
                if (p.hasNonNull("minWbRatio")) {
                    s.setMinWbRatio(p.get("minWbRatio").decimalValue());
                }
                if (p.hasNonNull("maxDeviationPct")) {
                    s.setMaxDeviationPct(p.get("maxDeviationPct").decimalValue());
                }
                if (p.hasNonNull("minWeiCha")) {
                    s.setMinWeiCha(p.get("minWeiCha").decimalValue());
                }
                if (p.hasNonNull("minVolSurge")) {
                    s.setMinVolSurge(p.get("minVolSurge").decimalValue());
                }
                if (p.hasNonNull("decisionIntervalSec")) {
                    s.setDecisionIntervalSec(Math.max(30, p.get("decisionIntervalSec").asInt(60)));
                }
            }
            s.setPendingJson(null);
            repository.saveStrategy(s);
            log.info("[quant] 策略 {} 的待生效变更已合并（标的 {}）", s.getId(), s.getWatchCodes());
            return true;
        } catch (Exception e) {
            log.warn("[quant] 策略 {} 的待生效变更解析失败，保持原样：{}", s.getId(), e.getMessage());
            return false;
        }
    }

    /** T+1：开盘前把每条持仓的可用数量对齐到持仓数量 */
    /**
     * 开盘前放开 T+1：把可用数量对齐到持仓数量。
     *
     * <p><b>只放开「今天之前买的」</b>。这一步本来只在开盘前跑，但 {@link #initToday()} 是
     * 「发现今天没初始化过就补一次」—— 服务在盘中重启时也会补跑，如果无条件放开，
     * 当天刚买的份额立刻变成可卖，T+1 就形同虚设。所以按 {@code lastBuyDate} 卡一道。
     */
    private int resetAvailableQty() {
        String today = LocalDate.now().toString();
        int count = 0;
        for (Long userId : repository.listStrategyUserIds()) {
            List<QuantStrategy> strategies = repository.listStrategies(userId);
            for (QuantStrategy s : strategies) {
                for (QuantHolding h : repository.listHoldings(s.getId())) {
                    String lastBuy = h.getLastBuyDate();
                    if (lastBuy != null && lastBuy.compareTo(today) >= 0) {
                        continue;
                    }
                    if (h.getAvailableQty() != h.getQuantity()) {
                        h.setAvailableQty(h.getQuantity());
                        repository.saveHolding(h);
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /* ==================== 结算 ==================== */

    /** 收盘结算（也可由超管手动触发） */
    public synchronized void settleToday() {
        String day = LocalDate.now().toString();
        try {
            int n = snapshot.settleDaily();
            settledDay = day;
            log.info("[quant] {} 收盘结算完成，{} 个策略", day, n);
        } catch (RuntimeException e) {
            log.warn("[quant] 收盘结算失败：{}", e.getMessage());
        }
    }
}
