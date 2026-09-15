package com.stock.kline.account;

import java.math.BigDecimal;

/**
 * 账户列表上的一行（一级视图）：账户名 + 这一本账的实时汇总。
 *
 * <p>与 {@link AccountSummary} 的区别是<b>不带持仓明细</b> —— 列表页一次要列所有账户，
 * 把每本账的持仓全塞回去纯属浪费，明细进二级视图再取。
 */
public class AccountView {

    private long id;
    /** 账户名称 */
    private String name;
    /** 账户期初市值（元） */
    private BigDecimal initValue = BigDecimal.ZERO;
    /** 可用金额（元） */
    private BigDecimal cash = BigDecimal.ZERO;
    /** 持仓市值（元） */
    private BigDecimal positionValue = BigDecimal.ZERO;
    /** 当前总市值（元）= 持仓市值 + 可用金额 */
    private BigDecimal totalValue = BigDecimal.ZERO;
    /** 当日收益（元） */
    private BigDecimal dayProfit = BigDecimal.ZERO;
    /** 当日收益率（%）：拿不到昨收时为 null */
    private BigDecimal dayProfitPercent;
    /** 总收益（元）= 当前总市值 - 期初市值 */
    private BigDecimal totalProfit = BigDecimal.ZERO;
    /** 总收益率（%）：期初市值为 0 时为 null */
    private BigDecimal totalProfitPercent;
    /** 持仓条数 */
    private int positionCount;
    private String remark;
    private String updateTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getInitValue() {
        return initValue;
    }

    public void setInitValue(BigDecimal initValue) {
        this.initValue = initValue;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(BigDecimal cash) {
        this.cash = cash;
    }

    public BigDecimal getPositionValue() {
        return positionValue;
    }

    public void setPositionValue(BigDecimal positionValue) {
        this.positionValue = positionValue;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public BigDecimal getDayProfit() {
        return dayProfit;
    }

    public void setDayProfit(BigDecimal dayProfit) {
        this.dayProfit = dayProfit;
    }

    public BigDecimal getDayProfitPercent() {
        return dayProfitPercent;
    }

    public void setDayProfitPercent(BigDecimal dayProfitPercent) {
        this.dayProfitPercent = dayProfitPercent;
    }

    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(BigDecimal totalProfit) {
        this.totalProfit = totalProfit;
    }

    public BigDecimal getTotalProfitPercent() {
        return totalProfitPercent;
    }

    public void setTotalProfitPercent(BigDecimal totalProfitPercent) {
        this.totalProfitPercent = totalProfitPercent;
    }

    public int getPositionCount() {
        return positionCount;
    }

    public void setPositionCount(int positionCount) {
        this.positionCount = positionCount;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
