package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;

/**
 * 每日账户快照（需求 v2.0 第 3 条：每日收益落盘）。
 *
 * <p>一天一条，收盘后写。所有收益字段都是<b>算好落盘</b>的，不在查询时现算 ——
 * 因为「当日盈亏」依赖「昨日总资产」这个只存在于历史里的值，
 * 现算要么回溯重算全部历史，要么依赖外部状态，都不如落一条来得稳。
 *
 * <p><b>月收益不单独建表</b>：按月对 {@link #dayPnl} 求和即可，
 * 单独存一份会和日数据重复，还得处理补数据、重算的一致性问题。
 */
public class QuantDaily {

    private long id;
    private long strategyId;
    private long userId;
    /** 交易日 yyyy-MM-dd */
    private String tradeDate;

    private BigDecimal cash = BigDecimal.ZERO;
    /** 收盘持仓市值 */
    private BigDecimal marketValue = BigDecimal.ZERO;
    /** 总资产 = 现金 + 持仓市值 */
    private BigDecimal totalAsset = BigDecimal.ZERO;

    /** 当日盈亏 = 今日总资产 - 昨日总资产（首日按初始资金） */
    private BigDecimal dayPnl = BigDecimal.ZERO;
    /** 当日收益率（%） */
    private BigDecimal dayPnlPct = BigDecimal.ZERO;
    /** 累计盈亏 = 总资产 - 初始资金 */
    private BigDecimal cumPnl = BigDecimal.ZERO;
    /** 累计收益率（%） */
    private BigDecimal cumPnlPct = BigDecimal.ZERO;

    private int holdingCount;

    private String createTime;

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

    public BigDecimal getDayPnl() {
        return dayPnl;
    }

    public void setDayPnl(BigDecimal dayPnl) {
        this.dayPnl = dayPnl;
    }

    public BigDecimal getDayPnlPct() {
        return dayPnlPct;
    }

    public void setDayPnlPct(BigDecimal dayPnlPct) {
        this.dayPnlPct = dayPnlPct;
    }

    public BigDecimal getCumPnl() {
        return cumPnl;
    }

    public void setCumPnl(BigDecimal cumPnl) {
        this.cumPnl = cumPnl;
    }

    public BigDecimal getCumPnlPct() {
        return cumPnlPct;
    }

    public void setCumPnlPct(BigDecimal cumPnlPct) {
        this.cumPnlPct = cumPnlPct;
    }

    public int getHoldingCount() {
        return holdingCount;
    }

    public void setHoldingCount(int holdingCount) {
        this.holdingCount = holdingCount;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }
}
