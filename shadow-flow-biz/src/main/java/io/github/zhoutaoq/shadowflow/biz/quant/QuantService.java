package io.github.zhoutaoq.shadowflow.biz.quant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化策略的业务层：增删改查、账户视图、交易流水、收益曲线。
 *
 * <p><b>哪些改动立即生效、哪些要等 0 点</b>（需求第 5 条）：
 * <ul>
 *   <li>立即生效：名称、备注、启停。它们是运营属性，不改变交易逻辑；</li>
 *   <li>次日 0 点生效：标的集合、全部交易参数。写进 {@code pendingJson}，
 *       由 {@code QuantTask} 在次日合并。理由是盘中改参数会让当天出现两套判断口径，
 *       事后回看成交记录根本说不清那笔按哪套规则下的。</li>
 * </ul>
 *
 * <p><b>初始资金</b>：改它等于重开一本账 —— 清持仓、清流水、现金回到新值。
 * 只改数字不动持仓，会让「累计收益 = 总资产 - 初始资金」变成一个没有意义的数。
 */
@Service
public class QuantService {

    private static final Logger log = LoggerFactory.getLogger(QuantService.class);

    private static final BigDecimal MIN_CAPITAL = BigDecimal.valueOf(10_000);
    private static final BigDecimal MAX_CAPITAL = BigDecimal.valueOf(10_000_000);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int MAX_NAME_LEN = 32;

    private final QuantRepository repository;
    private final EastmoneyClient em;
    private final QuantUniverseService universe;
    private final QuantTask task;
    private final QuantSnapshotService snapshot;
    private final ObjectMapper mapper = new ObjectMapper();

    public QuantService(QuantRepository repository, EastmoneyClient em,
                        QuantUniverseService universe, QuantTask task, QuantSnapshotService snapshot) {
        this.repository = repository;
        this.em = em;
        this.universe = universe;
        this.task = task;
        this.snapshot = snapshot;
    }

    /* ==================== 策略 ==================== */

    /** 列表：每本账带一份账户汇总，页面第一屏就能看到收益 */
    public List<Map<String, Object>> list(long userId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuantStrategy s : repository.listStrategies(userId)) {
            out.add(view(s));
        }
        return out;
    }

    /** 账户汇总视图 */
    public Map<String, Object> view(QuantStrategy s) {
        List<QuantHolding> holdings = repository.listHoldings(s.getId());
        Map<String, QuantQuote> snapshots = fetchQuotes(holdings);
        BigDecimal marketValue = marketValue(holdings, snapshots);
        BigDecimal cash = nz(s.getCash());
        BigDecimal total = cash.add(marketValue);
        BigDecimal base = baseAsset(s);
        BigDecimal init = nz(s.getInitCapital());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("remark", s.getRemark());
        m.put("enabled", s.isEnabled());
        m.put("initCapital", init);
        m.put("cash", cash);
        m.put("marketValue", marketValue.setScale(2, BigDecimal.ROUND_HALF_UP));
        m.put("totalAsset", total.setScale(2, BigDecimal.ROUND_HALF_UP));
        m.put("dayPnl", total.subtract(base).setScale(2, BigDecimal.ROUND_HALF_UP));
        m.put("dayPnlPct", ratio(total.subtract(base), base));
        m.put("cumPnl", total.subtract(init).setScale(2, BigDecimal.ROUND_HALF_UP));
        m.put("cumPnlPct", ratio(total.subtract(init), init));
        m.put("holdingCount", holdings.size());
        m.put("pending", s.getPendingJson() != null && !s.getPendingJson().trim().isEmpty());
        m.putAll(params(s));
        return m;
    }

    /** 创建：初始资金 1 万 ~ 1000 万，默认 10 万 */
    /**
     * 新建策略。
     *
     * @param positions 初始持仓：[{code, name, quantity, costPrice, buyDate}]；
     *                  这些仓位的成本是从 {@code initCapital} 里出的，先扣成本，剩下的才是可用资金
     */
    public QuantStrategy create(long userId, String name, BigDecimal initCapital, String remark,
                                List<Map<String, Object>> positions) {
        String n = checkName(name, null);
        BigDecimal capital = initCapital == null ? BigDecimal.valueOf(100_000) : initCapital;
        if (capital.compareTo(MIN_CAPITAL) < 0 || capital.compareTo(MAX_CAPITAL) > 0) {
            throw new IllegalArgumentException("初始资金需在 1 万 ~ 1000 万之间");
        }
        List<QuantHolding> initial = parseInitialHoldings(positions);
        BigDecimal cost = BigDecimal.ZERO;
        for (QuantHolding h : initial) {
            cost = cost.add(h.getCostPrice().multiply(BigDecimal.valueOf(h.getQuantity())));
        }
        if (cost.compareTo(capital) > 0) {
            throw new IllegalArgumentException("初始持仓成本 " + cost.setScale(2, BigDecimal.ROUND_HALF_UP)
                    + " 元已超过初始资金，可用金额会变成负数");
        }
        QuantStrategy s = new QuantStrategy();
        s.setUserId(userId);
        s.setName(n);
        s.setRemark(remark);
        s.setEnabled(true);
        s.setInitCapital(capital);
        // 持仓成本是从这笔初始资金里掏的，先扣掉，剩下的才是能开新仓的钱
        s.setCash(capital.subtract(cost).setScale(2, BigDecimal.ROUND_HALF_UP));
        s.setWatchCodes("");
        QuantStrategy created = repository.createStrategy(s);
        for (QuantHolding h : initial) {
            h.setStrategyId(created.getId());
            h.setUserId(userId);
            repository.saveHolding(h);
        }
        universe.refreshOne(created);
        return created;
    }

    /**
     * 初始持仓入库前的整理：校验 + 按买入日定可用数量。
     *
     * <p>买入日就是 T+1 的判据 —— 写的是今天，当天一股都不能卖（{@code availableQty = 0}）；
     * 写的是历史日期，说明这笔早就在手上，立刻可卖。之后每个交易日开盘前
     * {@code QuantTask} 都会把可用数量对齐到持仓数量，所以这里只要把第一天定对。
     */
    private List<QuantHolding> parseInitialHoldings(List<Map<String, Object>> raw) {
        List<QuantHolding> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        if (raw.size() > 5) {
            throw new IllegalArgumentException("初始持仓最多 5 只");
        }
        String today = LocalDate.now().toString();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Map<String, Object> m : raw) {
            if (m == null || m.get("code") == null) {
                continue;
            }
            String code = String.valueOf(m.get("code")).trim();
            if (!code.matches("\\d{6}")) {
                throw new IllegalArgumentException("代码「" + code + "」不是 6 位数字");
            }
            if (!seen.add(code)) {
                throw new IllegalArgumentException("初始持仓里 " + code + " 重复了");
            }
            int qty = intOf(m.get("quantity"));
            if (qty <= 0) {
                throw new IllegalArgumentException(code + " 的初始数量必须大于 0");
            }
            BigDecimal cost = decimalOf(m.get("costPrice"));
            if (cost == null || cost.signum() <= 0) {
                throw new IllegalArgumentException(code + " 的初始成本价必须大于 0");
            }
            String buyDate = m.get("buyDate") == null ? null : String.valueOf(m.get("buyDate")).trim();
            if (buyDate == null || !buyDate.matches("\\d{4}-\\d{2}-\\d{2}")) {
                buyDate = today;
            }
            QuantHolding h = new QuantHolding();
            h.setCode(code);
            h.setName(m.get("name") == null ? code : String.valueOf(m.get("name")).trim());
            h.setQuantity(qty);
            h.setCostPrice(cost);
            h.setAvailableQty(buyDate.compareTo(today) < 0 ? qty : 0);
            h.setOpenDate(buyDate);
            h.setLastBuyDate(buyDate);
            // 移动止盈的峰值从成本价起算，别一上来就按 0 判回撤
            h.setPeakPrice(cost);
            out.add(h);
        }
        return out;
    }

    private static int intOf(Object v) {
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static BigDecimal decimalOf(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 更新：运营属性立即生效，交易逻辑写进待生效区。
     *
     * @param codes  新的标的集合（逗号分隔），null = 不改
     * @param params 参数变更，null = 不改
     */
    public QuantStrategy update(long userId, long id, String name, String remark, Boolean enabled,
                                String codes, Map<String, Object> params, BigDecimal initCapital) {
        QuantStrategy s = require(userId, id);
        if (name != null) {
            s.setName(checkName(name, s.getId()));
        }
        if (remark != null) {
            s.setRemark(remark);
        }
        if (enabled != null) {
            s.setEnabled(enabled);
        }
        if (initCapital != null && initCapital.compareTo(nz(s.getInitCapital())) != 0) {
            resetAccount(s, initCapital);
        }
        boolean pending = false;
        Map<String, Object> body = new LinkedHashMap<>();
        if (codes != null) {
            body.put("codes", normalizeCodes(codes));
            pending = true;
        }
        if (params != null && !params.isEmpty()) {
            body.put("params", params);
            pending = true;
        }
        if (pending) {
            try {
                s.setPendingJson(mapper.writeValueAsString(body));
            } catch (Exception e) {
                throw new IllegalStateException("参数保存失败：" + e.getMessage(), e);
            }
        }
        repository.saveStrategy(s);
        universe.refreshOne(s);
        return s;
    }

    /** 改初始资金 = 重开一本账：清持仓与流水，现金回到新值 */
    private void resetAccount(QuantStrategy s, BigDecimal capital) {
        if (capital.compareTo(MIN_CAPITAL) < 0 || capital.compareTo(MAX_CAPITAL) > 0) {
            throw new IllegalArgumentException("初始资金需在 1 万 ~ 1000 万之间");
        }
        for (QuantHolding h : repository.listHoldings(s.getId())) {
            repository.deleteHolding(s.getId(), h.getId());
        }
        s.setInitCapital(capital);
        s.setCash(capital);
        log.info("[quant] 策略 {} 重置账户，初始资金 {}", s.getId(), capital);
    }

    public void delete(long userId, long id) {
        require(userId, id);
        repository.deleteStrategy(userId, id);
        universe.remove(id);
    }

    /* ==================== 持仓 ==================== */

    /** 持仓明细：带现价、市值、浮动盈亏、今日盈亏 */
    public List<Map<String, Object>> positions(long userId, long strategyId) {
        require(userId, strategyId);
        List<QuantHolding> holdings = repository.listHoldings(strategyId);
        Map<String, QuantQuote> snapshots = fetchQuotes(holdings);
        List<Map<String, Object>> out = new ArrayList<>();
        for (QuantHolding h : holdings) {
            QuantQuote q = snapshots.get(h.getCode());
            BigDecimal price = q != null && q.getPrice().signum() > 0
                    ? q.getPrice() : nz(h.getCostPrice());
            BigDecimal mv = price.multiply(BigDecimal.valueOf(h.getQuantity()));
            BigDecimal cost = nz(h.getCostPrice());
            BigDecimal pnl = price.subtract(cost).multiply(BigDecimal.valueOf(h.getQuantity()));

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", h.getId());
            m.put("code", h.getCode());
            m.put("name", h.getName());
            m.put("quantity", h.getQuantity());
            m.put("availableQty", h.getAvailableQty());
            m.put("costPrice", cost);
            m.put("price", price);
            m.put("changePct", q == null ? BigDecimal.ZERO : q.getChangePct());
            m.put("marketValue", mv.setScale(2, BigDecimal.ROUND_HALF_UP));
            m.put("pnl", pnl.setScale(2, BigDecimal.ROUND_HALF_UP));
            m.put("pnlPct", ratio(pnl, cost.multiply(BigDecimal.valueOf(h.getQuantity()))));
            m.put("peakPrice", h.getPeakPrice());
            m.put("openDate", h.getOpenDate());
            out.add(m);
        }
        return out;
    }

    /* ==================== 流水与收益 ==================== */

    public List<QuantTrade> trades(long userId, long strategyId, int limit) {
        require(userId, strategyId);
        return repository.listTrades(strategyId, limit <= 0 ? 200 : limit);
    }

    public List<QuantDaily> dailies(long userId, long strategyId, String from, String to) {
        require(userId, strategyId);
        return repository.listDailies(strategyId, from, to);
    }

    public List<Map<String, Object>> monthly(long userId, long strategyId, String from, String to) {
        require(userId, strategyId);
        return snapshot.monthly(strategyId, from, to);
    }

    public List<QuantIntradayPoint> intraday(long userId, long strategyId, String date) {
        require(userId, strategyId);
        String d = date == null || date.trim().isEmpty() ? LocalDate.now().toString() : date.trim();
        return repository.listIntraday(strategyId, d);
    }

    /* ==================== 手动触发（超管） ==================== */

    /** 需求第 8 条：超管手动初始化，立即刷新标的与指标 */
    public Map<String, Object> initNow() {
        task.initToday();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("strategies", universe.allCodes().isEmpty() ? 0 : repository.listEnabledStrategies().size());
        m.put("codes", universe.allCodes().size());
        m.put("time", java.time.LocalDateTime.now().toString());
        return m;
    }

    /* ==================== 内部 ==================== */

    public QuantStrategy require(long userId, long id) {
        QuantStrategy s = repository.findStrategy(userId, id);
        if (s == null) {
            throw new IllegalArgumentException("策略不存在或无权访问");
        }
        return s;
    }

    private Map<String, QuantQuote> fetchQuotes(List<QuantHolding> holdings) {
        if (holdings.isEmpty()) {
            return new LinkedHashMap<>();
        }
        List<String> codes = new ArrayList<>();
        for (QuantHolding h : holdings) {
            codes.add(h.getCode());
        }
        return em.snapshots(codes);
    }

    private static BigDecimal marketValue(List<QuantHolding> holdings, Map<String, QuantQuote> snapshots) {
        BigDecimal sum = BigDecimal.ZERO;
        for (QuantHolding h : holdings) {
            QuantQuote q = snapshots.get(h.getCode());
            BigDecimal price = q != null && q.getPrice().signum() > 0
                    ? q.getPrice() : nz(h.getCostPrice());
            sum = sum.add(price.multiply(BigDecimal.valueOf(h.getQuantity())));
        }
        return sum;
    }

    /** 昨日总资产：没有历史就用初始资金 */
    private BigDecimal baseAsset(QuantStrategy s) {
        List<QuantDaily> dailies = repository.listDailies(s.getId(), null, null);
        String today = LocalDate.now().toString();
        BigDecimal base = nz(s.getInitCapital());
        for (QuantDaily d : dailies) {
            if (d.getTradeDate() != null && d.getTradeDate().compareTo(today) < 0
                    && d.getTotalAsset() != null && d.getTotalAsset().signum() > 0) {
                base = d.getTotalAsset();
            }
        }
        return base;
    }

    private Map<String, Object> params(QuantStrategy s) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("watchCodes", s.getWatchCodes() == null ? "" : s.getWatchCodes());
        p.put("minScore", s.getMinScore());
        p.put("maxHoldings", s.getMaxHoldings());
        p.put("maxPositionPct", s.getMaxPositionPct());
        p.put("stopLossPct", s.getStopLossPct());
        p.put("takeProfitPct", s.getTakeProfitPct());
        p.put("trailDrawdownPct", s.getTrailDrawdownPct());
        p.put("decisionIntervalSec", s.getDecisionIntervalSec());
        p.put("minVolumeRatio", s.getMinVolumeRatio());
        p.put("minMainInflow", s.getMinMainInflow());
        p.put("minMainInflow5d", s.getMinMainInflow5d());
        p.put("requireMacdGolden", s.isRequireMacdGolden());
        p.put("minWbRatio", s.getMinWbRatio());
        p.put("maxDeviationPct", s.getMaxDeviationPct());
        p.put("minWeiCha", s.getMinWeiCha());
        return p;
    }

    private static String checkName(String name, Long selfId) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) {
            throw new IllegalArgumentException("策略名称不能为空");
        }
        if (n.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("策略名称不能超过 " + MAX_NAME_LEN + " 个字");
        }
        return n;
    }

    private static String normalizeCodes(String raw) {
        if (raw == null) {
            return "";
        }
        List<String> out = new ArrayList<>();
        for (String part : raw.split("[,，;；\\s]+")) {
            String code = EastmoneyClient.normalize(part);
            if (!code.isEmpty() && !out.contains(code)) {
                out.add(code);
            }
        }
        return String.join(",", out);
    }

    private static BigDecimal ratio(BigDecimal part, BigDecimal base) {
        if (base == null || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return part.multiply(HUNDRED).divide(base, 2, BigDecimal.ROUND_HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
