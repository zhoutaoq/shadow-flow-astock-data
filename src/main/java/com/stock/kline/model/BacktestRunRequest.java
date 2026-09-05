package com.stock.kline.model;

import java.math.BigDecimal;

/**
 * 发起一次回测的请求。
 *
 * <p>约定：所有比率一律用小数传递（页面按 % 录入，提交前换算）。
 * 例：手续费滑点 0.01% 传 0.0001；涨幅 5% 传 0.05。
 */
public class BacktestRunRequest {

    private String stockCode;
    private String market = "stock";
    private String startDate;
    private String endDate;
    /** 账户起始金额，必须 &gt; 0 */
    private BigDecimal initAmount = new BigDecimal("100000");
    /** 摩擦成本费率（小数），默认 0.01% = 0.0001，必须 &gt;= 0 */
    private BigDecimal feeRate = new BigDecimal("0.0001");
    private BacktestRule buyRule;
    private BacktestRule sellRule;
    /** 前端生成的任务 id，用于轮询执行进度；不传则不记录进度 */
    private String taskId;

    /**
     * 涨跌停无法成交时的处理策略（信号日 T 涨跌停 → 收盘价在板上不保证成交）。
     *
     * <ul>
     *   <li>1 = 无视：仍按 T 日收盘价成交（保持原有行为）</li>
     *   <li>2 = 次日开盘非涨跌停立即成交</li>
     *   <li>3 = 次日开盘非涨跌停、且相对 T 日收盘价的涨跌幅满足 {@link #limitRatio} 才成交</li>
     * </ul>
     *
     * <p>2 / 3 下：买入遇次日涨停开盘、卖出遇次日跌停开盘，一律作废不再尝试。
     */
    private int limitStrategy = 1;

    /** 策略 3 的涨跌幅阈值（小数），例：0.03 = 3%；为 null 时等同策略 2 */
    private BigDecimal limitRatio;

    /**
     * 策略 3 的比较方向：
     * <ul>
     *   <li>gt = 涨幅超过 {@link #limitRatio} 才成交</li>
     *   <li>lt = 跌幅超过 {@link #limitRatio} 才成交</li>
     * </ul>
     */
    private String limitOp = "gt";

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

    public BacktestRule getBuyRule() {
        return buyRule;
    }

    public void setBuyRule(BacktestRule buyRule) {
        this.buyRule = buyRule;
    }

    public BacktestRule getSellRule() {
        return sellRule;
    }

    public void setSellRule(BacktestRule sellRule) {
        this.sellRule = sellRule;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public int getLimitStrategy() {
        return limitStrategy;
    }

    public void setLimitStrategy(int limitStrategy) {
        this.limitStrategy = limitStrategy;
    }

    public BigDecimal getLimitRatio() {
        return limitRatio;
    }

    public void setLimitRatio(BigDecimal limitRatio) {
        this.limitRatio = limitRatio;
    }

    public String getLimitOp() {
        return limitOp;
    }

    public void setLimitOp(String limitOp) {
        this.limitOp = limitOp;
    }
}
