package com.stock.kline.model;

import java.math.BigDecimal;

/** waizao_backtest_daily 回测每日账户快照（区间内每个交易日一条） */
public class BacktestDaily {

    private Long id;
    private Long backtestId;
    private String tradeDate;
    private BigDecimal closePrice;
    /** 当日收盘后现金 */
    private BigDecimal cash;
    /** 当日收盘后持仓股数 */
    private int positionShares;
    /** 当日收盘后持仓市值 */
    private BigDecimal positionValue;
    /** 账户总金额 = 现金 + 持仓市值 */
    private BigDecimal totalAmount;
    /** 盈亏金额 = 账户总额 - 起始金额 */
    private BigDecimal profitAmount;
    /** 盈亏比例（小数） */
    private BigDecimal profitRatio;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBacktestId() {
        return backtestId;
    }

    public void setBacktestId(Long backtestId) {
        this.backtestId = backtestId;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(BigDecimal closePrice) {
        this.closePrice = closePrice;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(BigDecimal cash) {
        this.cash = cash;
    }

    public int getPositionShares() {
        return positionShares;
    }

    public void setPositionShares(int positionShares) {
        this.positionShares = positionShares;
    }

    public BigDecimal getPositionValue() {
        return positionValue;
    }

    public void setPositionValue(BigDecimal positionValue) {
        this.positionValue = positionValue;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public void setTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public BigDecimal getProfitAmount() {
        return profitAmount;
    }

    public void setProfitAmount(BigDecimal profitAmount) {
        this.profitAmount = profitAmount;
    }

    public BigDecimal getProfitRatio() {
        return profitRatio;
    }

    public void setProfitRatio(BigDecimal profitRatio) {
        this.profitRatio = profitRatio;
    }
}
