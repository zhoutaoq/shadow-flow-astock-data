package io.github.zhoutaoq.shadowflow.biz.model;

import java.math.BigDecimal;

/** waizao_backtest 回测主表（一次回测一条，买卖策略以 JSON 存放） */
public class Backtest {

    private Long id;
    private String stockCode;
    private String market = "stock";
    private String startDate;
    private String endDate;
    /** 账户起始金额 */
    private BigDecimal initAmount;
    /** 摩擦成本费率（小数），0.01% = 0.0001 */
    private BigDecimal feeRate;
    /** 买入策略组 JSON */
    private String buyRule;
    /** 卖出策略组 JSON */
    private String sellRule;
    private BigDecimal finalAmount;
    private BigDecimal totalProfit;
    private BigDecimal profitRatio;
    private int tradeCount;
    private int tradeDays;
    private String createTime;

    /** 股票名称（非表字段，列表展示用） */
    private String stockName;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getInitAmount() {
        return initAmount;
    }

    public void setInitAmount(BigDecimal initAmount) {
        this.initAmount = initAmount;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }

    public String getBuyRule() {
        return buyRule;
    }

    public void setBuyRule(String buyRule) {
        this.buyRule = buyRule;
    }

    public String getSellRule() {
        return sellRule;
    }

    public void setSellRule(String sellRule) {
        this.sellRule = sellRule;
    }

    public BigDecimal getFinalAmount() {
        return finalAmount;
    }

    public void setFinalAmount(BigDecimal finalAmount) {
        this.finalAmount = finalAmount;
    }

    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(BigDecimal totalProfit) {
        this.totalProfit = totalProfit;
    }

    public BigDecimal getProfitRatio() {
        return profitRatio;
    }

    public void setProfitRatio(BigDecimal profitRatio) {
        this.profitRatio = profitRatio;
    }

    public int getTradeCount() {
        return tradeCount;
    }

    public void setTradeCount(int tradeCount) {
        this.tradeCount = tradeCount;
    }

    public int getTradeDays() {
        return tradeDays;
    }

    public void setTradeDays(int tradeDays) {
        this.tradeDays = tradeDays;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }
}
