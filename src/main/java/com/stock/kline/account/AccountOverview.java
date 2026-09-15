package com.stock.kline.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一级视图（账户列表）的返回体：各账户一行 + 全部账户的合计。
 *
 * <p>合计口径与单账户一致，只是把各本账的数字直接相加 ——
 * 「期初市值」与「当前总市值」相加后再算总收益率，而不是把各账户的收益率求平均，
 * 否则小账户的收益率会被放大成与大账户同权。
 */
public class AccountOverview {

    /** 各账户一行，按账户 id 升序 */
    private List<AccountView> accounts = new ArrayList<>();
    /** 账户数量 */
    private int accountCount;
    /** 全部账户期初市值合计（元） */
    private BigDecimal initValue = BigDecimal.ZERO;
    /** 全部账户可用金额合计（元） */
    private BigDecimal cash = BigDecimal.ZERO;
    /** 全部账户持仓市值合计（元） */
    private BigDecimal positionValue = BigDecimal.ZERO;
    /** 全部账户当前总市值合计（元） */
    private BigDecimal totalValue = BigDecimal.ZERO;
    /** 全部账户当日收益合计（元） */
    private BigDecimal dayProfit = BigDecimal.ZERO;
    /** 当日收益率（%）：任一账户拿不到昨收时为 null */
    private BigDecimal dayProfitPercent;
    /** 全部账户总收益合计（元） */
    private BigDecimal totalProfit = BigDecimal.ZERO;
    /** 总收益率（%）：合计期初市值为 0 时为 null */
    private BigDecimal totalProfitPercent;
    /** 全部账户持仓条数合计 */
    private int positionCount;

    public List<AccountView> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<AccountView> accounts) {
        this.accounts = accounts;
    }

    public int getAccountCount() {
        return accountCount;
    }

    public void setAccountCount(int accountCount) {
        this.accountCount = accountCount;
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
}
