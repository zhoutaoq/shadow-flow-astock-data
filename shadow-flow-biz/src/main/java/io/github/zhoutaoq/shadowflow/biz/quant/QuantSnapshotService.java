package io.github.zhoutaoq.shadowflow.biz.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 组合快照：日内分时 + 每日收益 + 每月收益（需求 v2.0 第 3 条）。
 *
 * <p>三类数据三种粒度，落盘位置也不同：
 * <ul>
 *   <li><b>日内分时</b>：每分钟一个点，只存当天，按天淘汰。用于画当日资产曲线；</li>
 *   <li><b>每日收益</b>：收盘后一条，永久保留。当日盈亏依赖「昨日总资产」，
 *       所以必须在当天算好落盘，不能在查询时现算；</li>
 *   <li><b>每月收益</b>：不落盘，按月对日数据求和。单独存一份会重复，
 *       还得处理补数据导致的不一致。</li>
 * </ul>
 */
@Service
public class QuantSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(QuantSnapshotService.class);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    /** 日内点保留天数 */
    private static final int INTRADAY_KEEP_DAYS = 60;

    private final QuantRepository repository;
    private final EastmoneyClient em;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuantSnapshotService(QuantRepository repository, EastmoneyClient em) {
        this.repository = repository;
        this.em = em;
    }

    /* ==================== 日内分时 ==================== */

    /**
     * 采集一轮日内点：每个启用策略一条。
     *
     * <p>同一分钟重复调用会覆盖（唯一键 strategyId + date + HH:mm），
     * 所以调度器抖动不会产生重复点。
     */
    public int captureIntraday() {
        List<QuantStrategy> strategies = repository.listEnabledStrategies();
        if (strategies.isEmpty()) {
            return 0;
        }
        List<String> codes = new ArrayList<>();
        Map<Long, List<QuantHolding>> holdingsMap = new LinkedHashMap<>();
        for (QuantStrategy s : strategies) {
            List<QuantHolding> hs = repository.listHoldings(s.getId());
            holdingsMap.put(s.getId(), hs);
            for (QuantHolding h : hs) {
                if (!codes.contains(h.getCode())) {
                    codes.add(h.getCode());
                }
            }
        }
        if (codes.isEmpty()) {
            return 0;
        }
        Map<String, QuantQuote> snapshots = em.snapshots(codes);
        String today = LocalDate.now().toString();
        String hm = LocalTime.now().withSecond(0).withNano(0).toString();
        if (hm.length() > 5) {
            hm = hm.substring(0, 5);
        }

        int count = 0;
        for (QuantStrategy s : strategies) {
            List<QuantHolding> hs = holdingsMap.get(s.getId());
            try {
                QuantIntradayPoint p = buildPoint(s, hs, snapshots, today, hm);
                repository.saveIntraday(p);
                repository.purgeIntradayBefore(s.getId(),
                        LocalDate.now().minusDays(INTRADAY_KEEP_DAYS).toString());
                count++;
            } catch (RuntimeException e) {
                log.warn("[quant] 策略 {} 采集分时点失败：{}", s.getId(), e.getMessage());
            }
        }
        return count;
    }

    private QuantIntradayPoint buildPoint(QuantStrategy s, List<QuantHolding> holdings,
                                          Map<String, QuantQuote> snapshots,
                                          String today, String hm) {
        BigDecimal marketValue = BigDecimal.ZERO;
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuantHolding h : holdings) {
            QuantQuote q = snapshots.get(h.getCode());
            BigDecimal price = q != null && q.getPrice().signum() > 0
                    ? q.getPrice() : (h.getCostPrice() == null ? BigDecimal.ZERO : h.getCostPrice());
            BigDecimal mv = price.multiply(BigDecimal.valueOf(h.getQuantity()));
            marketValue = marketValue.add(mv);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", h.getCode());
            row.put("name", h.getName());
            row.put("price", price);
            row.put("qty", h.getQuantity());
            row.put("mv", mv.setScale(2, BigDecimal.ROUND_HALF_UP));
            rows.add(row);
        }
        BigDecimal cash = s.getCash() == null ? BigDecimal.ZERO : s.getCash();
        BigDecimal total = cash.add(marketValue);

        QuantIntradayPoint p = new QuantIntradayPoint();
        p.setStrategyId(s.getId());
        p.setUserId(s.getUserId());
        p.setTradeDate(today);
        p.setTime(hm);
        p.setCash(cash);
        p.setMarketValue(marketValue);
        p.setTotalAsset(total);
        p.setDayPnlPct(dayPnlPct(s, total, today));
        try {
            p.setHoldingsJson(mapper.writeValueAsString(rows));
        } catch (Exception e) {
            p.setHoldingsJson("[]");
        }
        return p;
    }

    /** 当日收益率：基准是昨日总资产，没有历史就用初始资金 */
    private BigDecimal dayPnlPct(QuantStrategy s, BigDecimal total, String today) {
        BigDecimal base = baseAssetOf(s, today);
        if (base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return total.subtract(base).multiply(HUNDRED).divide(base, 4, BigDecimal.ROUND_HALF_UP);
    }

    /**
     * 昨日总资产：取「今天之前最近一条日快照」。
     *
     * <p>不能简单取 tradeDate = 昨天：周末、节假日、停牌都会没有昨天那条，
     * 那样每次长假后第一天都会被算成「相对初始资金」，收益率直接失真。
     */
    private BigDecimal baseAssetOf(QuantStrategy s, String today) {
        List<QuantDaily> dailies = repository.listDailies(s.getId(), null, null);
        BigDecimal base = s.getInitCapital() == null ? BigDecimal.ZERO : s.getInitCapital();
        for (QuantDaily d : dailies) {
            if (d.getTradeDate() != null && d.getTradeDate().compareTo(today) < 0) {
                if (d.getTotalAsset() != null && d.getTotalAsset().signum() > 0) {
                    base = d.getTotalAsset();
                }
            }
        }
        return base;
    }

    /* ==================== 每日结算 ==================== */

    /**
     * 收盘结算：给每个启用策略写一条日快照。
     *
     * <p>可以重复调用（同一天覆盖），所以收盘后补跑、重启后补结算都是安全的。
     */
    public int settleDaily() {
        List<QuantStrategy> strategies = repository.listEnabledStrategies();
        if (strategies.isEmpty()) {
            return 0;
        }
        List<String> codes = new ArrayList<>();
        Map<Long, List<QuantHolding>> holdingsMap = new LinkedHashMap<>();
        for (QuantStrategy s : strategies) {
            List<QuantHolding> hs = repository.listHoldings(s.getId());
            holdingsMap.put(s.getId(), hs);
            for (QuantHolding h : hs) {
                if (!codes.contains(h.getCode())) {
                    codes.add(h.getCode());
                }
            }
        }
        Map<String, QuantQuote> snapshots = codes.isEmpty() ? new LinkedHashMap<>() : em.snapshots(codes);
        String today = LocalDate.now().toString();

        int count = 0;
        for (QuantStrategy s : strategies) {
            try {
                QuantDaily d = buildDaily(s, holdingsMap.get(s.getId()), snapshots, today);
                repository.saveDaily(d);
                count++;
                log.info("[quant] 策略 {} 结算 {}：总资产 {}，当日 {}，累计 {}",
                        s.getId(), today, d.getTotalAsset(), d.getDayPnl(), d.getCumPnl());
            } catch (RuntimeException e) {
                log.warn("[quant] 策略 {} 结算失败：{}", s.getId(), e.getMessage());
            }
        }
        return count;
    }

    private QuantDaily buildDaily(QuantStrategy s, List<QuantHolding> holdings,
                                  Map<String, QuantQuote> snapshots, String today) {
        BigDecimal marketValue = BigDecimal.ZERO;
        int count = 0;
        for (QuantHolding h : holdings) {
            QuantQuote q = snapshots.get(h.getCode());
            BigDecimal price = q != null && q.getPrice().signum() > 0
                    ? q.getPrice() : (h.getCostPrice() == null ? BigDecimal.ZERO : h.getCostPrice());
            marketValue = marketValue.add(price.multiply(BigDecimal.valueOf(h.getQuantity())));
            count++;
        }
        BigDecimal cash = s.getCash() == null ? BigDecimal.ZERO : s.getCash();
        BigDecimal total = cash.add(marketValue);
        BigDecimal base = baseAssetOf(s, today);
        BigDecimal init = s.getInitCapital() == null ? BigDecimal.ZERO : s.getInitCapital();

        QuantDaily d = new QuantDaily();
        d.setStrategyId(s.getId());
        d.setUserId(s.getUserId());
        d.setTradeDate(today);
        d.setCash(cash);
        d.setMarketValue(marketValue);
        d.setTotalAsset(total);
        d.setDayPnl(total.subtract(base).setScale(4, BigDecimal.ROUND_HALF_UP));
        d.setDayPnlPct(base.signum() > 0
                ? total.subtract(base).multiply(HUNDRED).divide(base, 4, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO);
        d.setCumPnl(total.subtract(init).setScale(4, BigDecimal.ROUND_HALF_UP));
        d.setCumPnlPct(init.signum() > 0
                ? total.subtract(init).multiply(HUNDRED).divide(init, 4, BigDecimal.ROUND_HALF_UP)
                : BigDecimal.ZERO);
        d.setHoldingCount(count);
        return d;
    }

    /* ==================== 每月收益 ==================== */

    /**
     * 按月聚合：{@code yyyy-MM → {month, pnl, pct, days, startAsset, endAsset}}。
     *
     * <p>月收益率的算法是「月末总资产 ÷ 月初前一日的总资产 - 1」，
     * 不是把每天的涨跌幅相加 —— 后者会把复利算成单利，长期下来偏差很大。
     */
    public List<Map<String, Object>> monthly(long strategyId, String from, String to) {
        List<QuantDaily> dailies = repository.listDailies(strategyId, from, to);
        Map<String, List<QuantDaily>> byMonth = new TreeMap<>();
        for (QuantDaily d : dailies) {
            if (d.getTradeDate() == null || d.getTradeDate().length() < 7) {
                continue;
            }
            byMonth.computeIfAbsent(d.getTradeDate().substring(0, 7), k -> new ArrayList<>()).add(d);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        BigDecimal prevClose = null;
        for (Map.Entry<String, List<QuantDaily>> e : byMonth.entrySet()) {
            List<QuantDaily> rows = e.getValue();
            QuantDaily first = rows.get(0);
            QuantDaily last = rows.get(rows.size() - 1);
            BigDecimal start = prevClose != null ? prevClose
                    : (first.getTotalAsset() != null ? first.getTotalAsset() : BigDecimal.ZERO);
            BigDecimal end = last.getTotalAsset() == null ? BigDecimal.ZERO : last.getTotalAsset();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("month", e.getKey());
            row.put("days", rows.size());
            row.put("startAsset", start);
            row.put("endAsset", end);
            row.put("pnl", end.subtract(start).setScale(2, BigDecimal.ROUND_HALF_UP));
            row.put("pct", start.signum() > 0
                    ? end.subtract(start).multiply(HUNDRED).divide(start, 2, BigDecimal.ROUND_HALF_UP)
                    : BigDecimal.ZERO);
            out.add(row);
            prevClose = end;
        }
        return out;
    }
}
