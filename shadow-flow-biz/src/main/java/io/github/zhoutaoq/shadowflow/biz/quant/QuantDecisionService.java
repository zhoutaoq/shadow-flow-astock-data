package io.github.zhoutaoq.shadowflow.biz.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 决策与模拟撮合：一轮 = 取快照 → 算指标 → 先卖后买 → 落成交。
 *
 * <p><b>先卖后买</b>不是习惯问题：卖出释放的资金要参与当轮的买入预算，
 * 反过来做会让「满仓 + 该止损」的情况下永远腾不出仓位。
 *
 * <p><b>撮合规则</b>（需求第 9 条 + A 股基本规则）：
 * <ul>
 *   <li>一手 100 股，买入数量向下取整到 100 的整数倍，不足一手不买；</li>
 *   <li>T+1：当天买入的份额 {@code availableQty = 0}，次日开盘前由 {@code QuantInitTask} 放开；</li>
 *   <li>最多 5 只（参数可调但硬顶 5）；单票不超过总资产的 {@code maxPositionPct}%；</li>
 *   <li>费用 = 成交额 × 0.0001，买卖双边都收，口径与回测一致；</li>
 *   <li>不考虑分红、拆股、涨跌停无法成交等特殊场景。</li>
 * </ul>
 *
 * <p><b>不加仓</b>：已持有的标的不再买入。组合层面「一只票一个仓位」比「越涨越买」更可控，
 * 也避免同一只票的持仓成本被反复摊薄后失去止损基准。
 */
@Service
public class QuantDecisionService {

    private static final Logger log = LoggerFactory.getLogger(QuantDecisionService.class);

    /** 一手 = 100 股 */
    private static final int LOT = 100;
    /**
     * 滑点：万分之二。
     *
     * <p>模拟盘按「现价」成交是不真实的 —— 真实下单要么挂单等，要么吃点差价。
     * 买入往上抬 0.02%、卖出往下压 0.02%，一年下来这点差异足以让一个「纸上盈利」的策略露出真面目。
     */
    private static final BigDecimal SLIPPAGE = new BigDecimal("0.0002");
    /** 与回测同一口径的摩擦成本 */
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0001");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 交易时段：9:30-11:30 / 13:00-15:00 */
    private static final LocalTime AM_OPEN = LocalTime.of(9, 30);
    private static final LocalTime AM_CLOSE = LocalTime.of(11, 30);
    private static final LocalTime PM_OPEN = LocalTime.of(13, 0);
    private static final LocalTime PM_CLOSE = LocalTime.of(15, 0);

    private final QuantRepository repository;
    private final EastmoneyClient em;
    private final QuantIndicatorService indicators;
    private final QuantUniverseService universe;

    /** strategyId → 上一轮开跑时刻 */
    private final Map<Long, Long> lastRun = new ConcurrentHashMap<>();

    public QuantDecisionService(QuantRepository repository, EastmoneyClient em,
                                QuantIndicatorService indicators, QuantUniverseService universe) {
        this.repository = repository;
        this.em = em;
        this.indicators = indicators;
        this.universe = universe;
    }

    /**
     * 跑一轮：所有启用策略里「到点了」的那些各跑一次。
     *
     * @return 本轮成交笔数（买卖都算）
     */
    public synchronized int runOnce() {
        List<QuantStrategy> strategies = repository.listEnabledStrategies();
        if (strategies.isEmpty()) {
            return 0;
        }
        List<String> allCodes = universe.allCodes();
        if (allCodes.isEmpty()) {
            return 0;
        }
        Map<String, QuantQuote> snapshots = em.snapshots(allCodes);
        if (snapshots.isEmpty()) {
            log.warn("[quant] 本轮快照为空（数据源暂不可用），跳过");
            return 0;
        }
        MarketContext market = indicators.market();

        int trades = 0;
        for (QuantStrategy s : strategies) {
            if (!due(s)) {
                continue;
            }
            try {
                trades += runStrategy(s, market, snapshots);
            } catch (RuntimeException e) {
                log.warn("[quant] 策略 {}（{}）本轮异常：{}", s.getId(), s.getName(), e.getMessage());
            }
        }
        return trades;
    }

    /**
     * mock 验证入口：用给定快照与指标跑一轮策略，不碰外网。
     *
     * <p>为什么需要它：L1 是三道硬门槛（大盘 / 主力 / 位置），真实行情里要凑齐全部通过并不容易，
     * 买入链路因此一直没被真正跑通过。把指标做成可注入，就能把「判定之后」的下单、扣款、
     * 建仓、T+1 锁仓整条链路验证掉，不用干等一个恰好符合条件的交易日。
     *
     * <p>只由 mock 开关控制的那组接口调用，生产路径（{@link #runOnce()}）完全不受影响。
     */
    public int runMock(long strategyId, Map<String, QuantQuote> snapshots, Map<String, QuantMetrics> metrics) {
        QuantStrategy target = null;
        for (Long uid : repository.listStrategyUserIds()) {
            for (QuantStrategy s : repository.listStrategies(uid)) {
                if (s.getId() == strategyId) {
                    target = s;
                    break;
                }
            }
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("策略 " + strategyId + " 不存在");
        }
        return runStrategy(target, indicators.market(), snapshots, metrics);
    }

    /** 是否到该策略的决策间隔 */
    private boolean due(QuantStrategy s) {
        long now = System.currentTimeMillis();
        int interval = Math.max(30, s.getDecisionIntervalSec());
        Long last = lastRun.get(s.getId());
        if (last == null) {
            lastRun.put(s.getId(), now);
            return true;
        }
        if (now - last < interval * 1000L) {
            return false;
        }
        lastRun.put(s.getId(), now);
        return true;
    }

    /* ==================== 单个策略 ==================== */

    private int runStrategy(QuantStrategy s, MarketContext market, Map<String, QuantQuote> snapshots) {
        return runStrategy(s, market, snapshots, null);
    }

    /**
     * @param forced 非 null 时直接用这份指标，不去算实时数据 —— 只给 mock 验证用，
     *               生产路径走上面那个重载（forced = null）
     */
    int runStrategy(QuantStrategy s, MarketContext market, Map<String, QuantQuote> snapshots,
                    Map<String, QuantMetrics> forced) {
        List<QuantHolding> holdings = repository.listHoldings(s.getId());
        List<String> codes = universe.codesOf(s.getId());
        // mock 不受「标的次日生效」限制：注入的指标就是这一轮的候选
        if (forced != null && codes.isEmpty()) {
            codes = new ArrayList<>(forced.keySet());
        }
        if (codes.isEmpty()) {
            return 0;
        }

        // 每只票算一次指标：持仓与候选共用同一份，避免同一轮里出现两个结论
        Map<String, QuantMetrics> metrics = new ConcurrentHashMap<>();
        if (forced != null) {
            metrics.putAll(forced);
        } else {
            for (String code : codes) {
                QuantQuote q = snapshots.get(code);
                if (q == null) {
                    continue;
                }
                metrics.put(code, indicators.evaluate(q, em.mainFlow(code), em.dailyBars(code), market));
            }
        }

        int trades = 0;
        BigDecimal cash = s.getCash() == null ? BigDecimal.ZERO : s.getCash();
        String today = LocalDate.now().toString();

        /* ---------- 先卖：止损 / 止盈 / 移动止盈 / 主力转出 ---------- */
        for (QuantHolding h : holdings) {
            QuantQuote q = snapshots.get(h.getCode());
            if (q == null || q.getPrice() == null) {
                continue;
            }
            // 峰值要先更新再判断：当天创新高后又回落，才算「从最高回撤」
            BigDecimal peak = h.getPeakPrice() == null ? BigDecimal.ZERO : h.getPeakPrice();
            if (q.getPrice().compareTo(peak) > 0) {
                h.setPeakPrice(q.getPrice());
                repository.saveHolding(h);
            }
            String reason = sellReason(s, h, q, metrics.get(h.getCode()));
            if (reason == null) {
                continue;
            }
            if (h.getAvailableQty() <= 0) {
                log.info("[quant] 策略 {} {} 满足卖出条件（{}）但 T+1 不可卖，等次日",
                        s.getId(), h.getCode(), reason);
                continue;
            }
            cash = doSell(s, h, q, reason, cash, today);
            trades++;
        }

        /* ---------- 再买：按综合分从高到低，填满仓位为止 ---------- */
        List<QuantHolding> afterSell = repository.listHoldings(s.getId());
        int maxHold = Math.min(5, Math.max(1, s.getMaxHoldings()));
        int slots = maxHold - afterSell.size();
        if (slots <= 0) {
            s.setCash(cash);
            repository.saveStrategy(s);
            return trades;
        }
        Set<String> held = new HashSet<>();
        for (QuantHolding h : afterSell) {
            held.add(h.getCode());
        }

        List<QuantMetrics> candidates = new ArrayList<>();
        for (String code : codes) {
            if (held.contains(code)) {
                continue;
            }
            QuantMetrics m = metrics.get(code);
            if (m != null) {
                candidates.add(m);
            }
        }
        candidates.sort(Comparator.comparing(QuantMetrics::getTotalScore).reversed());

        // 每轮留一条摘要：不成交的时候要能从日志看出是「没数据」「L1 被拒」还是「分数不够」，
        // 否则盘后只能靠猜
        int l1Pass = 0;
        for (QuantMetrics m : candidates) {
            if (m.isL1Pass()) {
                l1Pass++;
            }
        }
        if (!candidates.isEmpty()) {
            QuantMetrics best = candidates.get(0);
            log.info("[quant] 策略 {} 候选 {} 只，L1 通过 {} 只，最高分 {}（{}），L1 未过原因：{}",
                    s.getId(), candidates.size(), l1Pass, best.getTotalScore(),
                    best.getCode(), best.getL1Fails().isEmpty() ? "无" : String.join("；", best.getL1Fails()));
        }

        BigDecimal totalAsset = totalAsset(cash, afterSell, snapshots);
        BigDecimal minScore = s.getMinScore() == null ? BigDecimal.valueOf(60) : s.getMinScore();
        BigDecimal maxPct = s.getMaxPositionPct() == null ? BigDecimal.valueOf(20) : s.getMaxPositionPct();

        for (QuantMetrics m : candidates) {
            if (slots <= 0) {
                break;
            }
            QuantQuote q = snapshots.get(m.getCode());
            if (q == null) {
                continue;
            }
            if (!m.isL1Pass()) {
                continue;
            }
            // 综合分只作参考展示，不作买入门槛（见 QuantMetrics 的说明：权重表是参考，不是判据）
            // 第二层：不追高 —— 现价离均价太远说明当天已经拉过一段
            if (m.getAvgScore() < 50) {
                continue;
            }
            // 用户在策略里设的硬门槛：量比 / 主力资金 / MACD / 委比 / 追高幅度
            String miss = hardFilterMiss(s, m, q);
            if (miss != null) {
                log.info("[quant] 策略 {} {} 综合分 {} 但卡在硬门槛：{}",
                        s.getId(), m.getCode(), m.getTotalScore(), miss);
                continue;
            }
            BigDecimal budget = totalAsset.multiply(maxPct).divide(HUNDRED, 2, BigDecimal.ROUND_HALF_UP);
            if (budget.compareTo(cash) > 0) {
                budget = cash;
            }
            int qty = lotsOf(budget, q.getPrice());
            if (qty < LOT) {
                continue;
            }
            cash = doBuy(s, m, q, qty, cash, today);
            slots--;
            trades++;
        }

        s.setCash(cash);
        repository.saveStrategy(s);
        return trades;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * 买入硬门槛：分数过了还不够，这几项得同时成立。
     *
     * <p>全部用 {@link QuantMetrics} / {@link QuantQuote} 里已经算好的原始值，不额外取数。
     * 返回 null = 全过；否则返回第一个不满足项的中文说明（写进日志，事后能看出为什么没买）。
     */
    private String hardFilterMiss(QuantStrategy s, QuantMetrics m, QuantQuote q) {
        BigDecimal minVr = s.getMinVolumeRatio();
        if (minVr != null && m.getVolumeRatio() != null
                && m.getVolumeRatio().compareTo(minVr) < 0) {
            return "量比 " + m.getVolumeRatio().toPlainString() + " < " + minVr.toPlainString();
        }
        // 主力资金接口给的是「元」，策略里让人填「万元」更好读
        BigDecimal minToday = s.getMinMainInflow();
        if (minToday != null) {
            BigDecimal todayWan = nz(m.getTodayMain())
                    .divide(BigDecimal.valueOf(10000), 2, BigDecimal.ROUND_HALF_UP);
            if (todayWan.compareTo(minToday) < 0) {
                return "今日主力 " + todayWan.toPlainString() + " 万 < " + minToday.toPlainString() + " 万";
            }
        }
        BigDecimal min5d = s.getMinMainInflow5d();
        if (min5d != null) {
            BigDecimal wan5 = nz(m.getMain5d())
                    .divide(BigDecimal.valueOf(10000), 2, BigDecimal.ROUND_HALF_UP);
            if (wan5.compareTo(min5d) < 0) {
                return "5 日主力 " + wan5.toPlainString() + " 万 < " + min5d.toPlainString() + " 万";
            }
        }
        if (s.isRequireMacdGolden() && nz(m.getMacdBar()).signum() <= 0) {
            return "MACD 未金叉（柱 " + nz(m.getMacdBar()).toPlainString() + "）";
        }
        BigDecimal minWb = s.getMinWbRatio();
        if (minWb != null && m.getWbRatio() != null && m.getWbRatio().compareTo(minWb) < 0) {
            return "委比 " + m.getWbRatio().toPlainString() + "% < " + minWb.toPlainString() + "%";
        }
        BigDecimal minWei = s.getMinWeiCha();
        if (minWei != null) {
            // 股 → 手，盘口习惯按手看
            BigDecimal weiShou = nz(q.getWeiCha())
                    .divide(BigDecimal.valueOf(100), 2, BigDecimal.ROUND_HALF_UP);
            if (weiShou.compareTo(minWei) < 0) {
                return "委差 " + weiShou.toPlainString() + " 手 < " + minWei.toPlainString() + " 手";
            }
        }
        BigDecimal maxDev = s.getMaxDeviationPct();
        if (maxDev != null) {
            BigDecimal dev = q.priceVsAvgPct();
            if (dev != null && dev.compareTo(maxDev) > 0) {
                return "现价高于均价 " + dev.toPlainString() + "% > " + maxDev.toPlainString() + "%";
            }
        }
        return null;
    }

    /* ==================== 卖出判定 ==================== */

    /**
     * 卖不卖，按优先级返回第一个成立的理由。
     *
     * <p>顺序是有讲究的：止损永远第一（保住本金），止盈第二（落袋），
     * 移动止盈第三（让利润跑一段再走），趋势破坏最后（它最容易被单日噪音误触发）。
     */
    private String sellReason(QuantStrategy s, QuantHolding h, QuantQuote q, QuantMetrics m) {
        BigDecimal cost = h.getCostPrice();
        if (cost == null || cost.signum() <= 0) {
            return null;
        }
        BigDecimal price = q.getPrice();
        BigDecimal stop = s.getStopLossPct() == null ? BigDecimal.valueOf(5) : s.getStopLossPct();
        BigDecimal take = s.getTakeProfitPct() == null ? BigDecimal.valueOf(15) : s.getTakeProfitPct();
        BigDecimal trail = s.getTrailDrawdownPct() == null ? BigDecimal.valueOf(8) : s.getTrailDrawdownPct();

        BigDecimal stopLine = cost.multiply(HUNDRED.subtract(stop)).divide(HUNDRED, 4, BigDecimal.ROUND_HALF_UP);
        if (price.compareTo(stopLine) <= 0) {
            return "止损：现价 " + price.toPlainString() + " 跌破 " + stopLine.toPlainString()
                    + "（成本 " + cost.toPlainString() + " -" + stop.toPlainString() + "%）";
        }

        BigDecimal takeLine = cost.multiply(HUNDRED.add(take)).divide(HUNDRED, 4, BigDecimal.ROUND_HALF_UP);
        if (price.compareTo(takeLine) >= 0) {
            return "止盈：现价 " + price.toPlainString() + " 达到 " + takeLine.toPlainString()
                    + "（成本 +" + take.toPlainString() + "%）";
        }

        // 移动止盈：只有浮盈超过止盈线一半才启用，否则刚建仓的正常波动就会被清出去
        BigDecimal peak = h.getPeakPrice();
        BigDecimal armLine = cost.multiply(HUNDRED.add(take.divide(BigDecimal.valueOf(2), 4,
                BigDecimal.ROUND_HALF_UP))).divide(HUNDRED, 4, BigDecimal.ROUND_HALF_UP);
        if (peak != null && peak.compareTo(armLine) >= 0) {
            BigDecimal trailLine = peak.multiply(HUNDRED.subtract(trail))
                    .divide(HUNDRED, 4, BigDecimal.ROUND_HALF_UP);
            if (price.compareTo(trailLine) <= 0) {
                return "移动止盈：最高 " + peak.toPlainString() + " 回撤到 " + price.toPlainString()
                        + "（回撤 " + trail.toPlainString() + "%）";
            }
        }

        // 趋势破坏：主力当天净流出且价格跌到均价线下方，两条同时成立才走
        if (m != null && m.getTodayMain().signum() < 0 && q.priceVsAvgPct().signum() < 0) {
            return "趋势破坏：主力净流出 " + m.getTodayMain().toPlainString()
                    + "，现价跌破当日均价 " + q.getAvgPrice().toPlainString();
        }
        return null;
    }

    /* ==================== 撮合 ==================== */

    private BigDecimal doSell(QuantStrategy s, QuantHolding h, QuantQuote q, String reason,
                              BigDecimal cash, String today) {
        int qty = Math.min(h.getAvailableQty(), h.getQuantity());
        qty = qty / LOT * LOT;
        if (qty <= 0) {
            return cash;
        }
        // 卖出价按滑点下压
        BigDecimal price = q.getPrice().multiply(BigDecimal.ONE.subtract(SLIPPAGE))
                .setScale(4, BigDecimal.ROUND_HALF_UP);
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(qty));
        BigDecimal fee = turnover.multiply(FEE_RATE).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal cost = h.getCostPrice() == null ? BigDecimal.ZERO : h.getCostPrice();
        BigDecimal realized = price.subtract(cost).multiply(BigDecimal.valueOf(qty))
                .subtract(fee).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal cashAfter = cash.add(turnover).subtract(fee);

        h.setQuantity(h.getQuantity() - qty);
        h.setAvailableQty(h.getAvailableQty() - qty);
        if (h.getQuantity() <= 0) {
            repository.deleteHolding(s.getId(), h.getId());
        } else {
            repository.saveHolding(h);
        }

        QuantTrade t = new QuantTrade();
        t.setStrategyId(s.getId());
        t.setUserId(s.getUserId());
        t.setAction(QuantTrade.ACTION_SELL);
        t.setCode(h.getCode());
        t.setName(h.getName());
        t.setPrice(price);
        t.setQuantity(qty);
        t.setTurnover(turnover);
        t.setFee(fee);
        t.setRealizedPnl(realized);
        t.setCashAfter(cashAfter);
        t.setReason(reason);
        t.setTradeDate(today);
        t.setTime(LocalDateTime.now().format(TS));
        repository.saveTrade(t);

        log.info("[quant] 策略 {} 卖出 {} {} 股 @ {}，盈亏 {}，原因：{}",
                s.getId(), h.getCode(), qty, price.toPlainString(), realized.toPlainString(), reason);
        return cashAfter;
    }

    private BigDecimal doBuy(QuantStrategy s, QuantMetrics m, QuantQuote q, int qty,
                             BigDecimal cash, String today) {
        // 买入价按滑点上浮
        BigDecimal price = q.getPrice().multiply(BigDecimal.ONE.add(SLIPPAGE))
                .setScale(4, BigDecimal.ROUND_HALF_UP);
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(qty));
        BigDecimal fee = turnover.multiply(FEE_RATE).setScale(2, BigDecimal.ROUND_HALF_UP);
        BigDecimal cashAfter = cash.subtract(turnover).subtract(fee);

        QuantHolding h = new QuantHolding();
        h.setStrategyId(s.getId());
        h.setUserId(s.getUserId());
        h.setCode(q.getCode());
        h.setName(q.getName());
        h.setQuantity(qty);
        h.setCostPrice(price);
        // T+1：当天买的当天不可卖
        h.setAvailableQty(0);
        h.setOpenDate(today);
        h.setLastBuyDate(today);
        h.setPeakPrice(price);
        repository.saveHolding(h);

        QuantTrade t = new QuantTrade();
        t.setStrategyId(s.getId());
        t.setUserId(s.getUserId());
        t.setAction(QuantTrade.ACTION_BUY);
        t.setCode(q.getCode());
        t.setName(q.getName());
        t.setPrice(price);
        t.setQuantity(qty);
        t.setTurnover(turnover);
        t.setFee(fee);
        t.setRealizedPnl(BigDecimal.ZERO);
        t.setCashAfter(cashAfter);
        t.setScore(m.getTotalScore());
        t.setReason(buyReason(m));
        t.setTradeDate(today);
        t.setTime(LocalDateTime.now().format(TS));
        repository.saveTrade(t);

        log.info("[quant] 策略 {} 买入 {} {} 股 @ {}，综合分 {}，{}",
                s.getId(), q.getCode(), qty, price.toPlainString(), m.getTotalScore(), t.getReason());
        return cashAfter;
    }

    /** 买入原因：把通过的几层写清楚，事后才知道当时是按什么买的 */
    private String buyReason(QuantMetrics m) {
        StringBuilder sb = new StringBuilder();
        sb.append("综合分 ").append(m.getTotalScore().toPlainString())
                .append("（大盘 ").append(m.getMarketScore())
                .append("/主力 ").append(m.getMainScore())
                .append("/位置 ").append(m.getPositionScore())
                .append("/量能 ").append(m.getVolumeScore())
                .append("/均价 ").append(m.getAvgScore()).append("）");
        if (m.getTodayMain().signum() > 0) {
            sb.append("；今日主力净流入 ").append(wan(m.getTodayMain()));
        }
        if (m.getVolumeRatio() != null && m.getVolumeRatio().signum() > 0) {
            sb.append("；量比 ").append(m.getVolumeRatio().toPlainString());
        }
        if (m.getAtr() != null && m.getAtr().signum() > 0) {
            sb.append("；ATR 止损 ").append(m.getStopPrice().toPlainString());
        }
        return sb.toString();
    }

    /* ==================== 工具 ==================== */

    private static int lotsOf(BigDecimal budget, BigDecimal price) {
        if (price == null || price.signum() <= 0) {
            return 0;
        }
        BigDecimal perLot = price.multiply(BigDecimal.valueOf(LOT))
                .multiply(BigDecimal.ONE.add(FEE_RATE));
        if (perLot.signum() <= 0) {
            return 0;
        }
        int lots = budget.divide(perLot, 0, BigDecimal.ROUND_DOWN).intValue();
        return Math.max(0, lots) * LOT;
    }

    private BigDecimal totalAsset(BigDecimal cash, List<QuantHolding> holdings,
                                  Map<String, QuantQuote> snapshots) {
        BigDecimal sum = cash;
        for (QuantHolding h : holdings) {
            QuantQuote q = snapshots.get(h.getCode());
            BigDecimal price = q != null && q.getPrice().signum() > 0
                    ? q.getPrice() : (h.getCostPrice() == null ? BigDecimal.ZERO : h.getCostPrice());
            sum = sum.add(price.multiply(BigDecimal.valueOf(h.getQuantity())));
        }
        return sum;
    }

    /** 是否在连续竞价时段（集合竞价不下单，避免拿到一个无法成交的价格） */
    public static boolean inTradingHours(LocalTime now) {
        boolean am = !now.isBefore(AM_OPEN) && now.isBefore(AM_CLOSE);
        boolean pm = !now.isBefore(PM_OPEN) && now.isBefore(PM_CLOSE);
        return am || pm;
    }

    private static String wan(BigDecimal v) {
        return v.divide(BigDecimal.valueOf(10000), 2, BigDecimal.ROUND_HALF_UP).toPlainString() + " 万";
    }
}
