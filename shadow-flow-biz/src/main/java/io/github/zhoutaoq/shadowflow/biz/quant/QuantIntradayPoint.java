package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;

/**
 * 日内分时点（需求 v2.0 第 3 条：每日持仓的日内分时落盘）。
 *
 * <p>一行 = 某个策略在某一分钟的总资产快照。页面按当日所有点连成一条资产曲线，
 * 并可用 {@link #holdingsJson} 还原那一刻每只持仓的价格与市值。
 *
 * <p><b>为什么存明细 JSON 而不是再建一张「持仓分时表」</b>：明细只在画图时整段读取，
 * 从不按股票维度单独查询；拆表会让一天 240 个点 × 5 只变成 1200 行，
 * 写入放大 5 倍、查询还要 join，收益为零。
 *
 * <p>粒度取 1 分钟：策略本身 60 秒一轮决策，比这更细没有意义；
 * 240 点/天/策略的体量在无数据库模式下也完全放得下。
 */
public class QuantIntradayPoint {

    private long id;
    private long strategyId;
    private long userId;
    /** 交易日 yyyy-MM-dd */
    private String tradeDate;
    /** 时刻 HH:mm */
    private String time;

    private BigDecimal cash = BigDecimal.ZERO;
    private BigDecimal marketValue = BigDecimal.ZERO;
    private BigDecimal totalAsset = BigDecimal.ZERO;
    /** 当日相对昨收的收益率（%） */
    private BigDecimal dayPnlPct = BigDecimal.ZERO;

    /**
     * 这一刻的持仓明细，形如
     * {@code [{"code":"600584","name":"长电科技","price":48.17,"qty":200,"mv":9634}]}。
     * 空仓时为 {@code []}。
     */
    private String holdingsJson;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(long strategyId) {
        this.strategyId = strategyId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(BigDecimal cash) {
        this.cash = cash;
    }

    public BigDecimal getMarketValue() {
        return marketValue;
    }

    public void setMarketValue(BigDecimal marketValue) {
        this.marketValue = marketValue;
    }

    public BigDecimal getTotalAsset() {
        return totalAsset;
    }

    public void setTotalAsset(BigDecimal totalAsset) {
        this.totalAsset = totalAsset;
    }

    public BigDecimal getDayPnlPct() {
        return dayPnlPct;
    }

    public void setDayPnlPct(BigDecimal dayPnlPct) {
        this.dayPnlPct = dayPnlPct;
    }

    public String getHoldingsJson() {
        return holdingsJson;
    }

    public void setHoldingsJson(String holdingsJson) {
        this.holdingsJson = holdingsJson;
    }
}
