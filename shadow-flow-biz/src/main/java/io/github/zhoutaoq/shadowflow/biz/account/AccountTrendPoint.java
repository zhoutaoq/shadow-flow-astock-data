package io.github.zhoutaoq.shadowflow.biz.account;

import java.math.BigDecimal;

/**
 * 账户在某个交易日的<b>一分钟快照</b>（需求 v1.6.7 第 ③ 条）。
 *
 * <p>只记「市值长什么样」，不记持仓明细：走势图要的是账户总市值的曲线，
 * 明细在 {@code AccountService#summary} 里随时能算，重复落盘只会让文件膨胀。
 *
 * <p>粒度是分钟，{@code minute} 形如 {@code 09:31}：同一账户同一天同一分钟只留一条
 * （仓储按 (accountId, date, minute) 做 upsert），这样定时任务重复触发、页面顺手补点时
 * 都不会写出一堆重复点。
 */
public class AccountTrendPoint {

    /** 分钟，HH:mm（交易时段内） */
    private String minute;
    /** 账户总市值（元）= 持仓市值 + 可用金额 */
    private BigDecimal totalValue = BigDecimal.ZERO;
    /** 持仓市值（元），画图时用来对照「是持仓涨了还是现金变了」 */
    private BigDecimal positionValue = BigDecimal.ZERO;
    /** 当日收益（元）：口径与页面一致（持仓浮动 + 当日已实现） */
    private BigDecimal dayProfit = BigDecimal.ZERO;

    public AccountTrendPoint() {
    }

    public AccountTrendPoint(String minute, BigDecimal totalValue, BigDecimal positionValue, BigDecimal dayProfit) {
        this.minute = minute;
        this.totalValue = totalValue;
        this.positionValue = positionValue;
        this.dayProfit = dayProfit;
    }

    public String getMinute() {
        return minute;
    }

    public void setMinute(String minute) {
        this.minute = minute;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public BigDecimal getPositionValue() {
        return positionValue;
    }

    public void setPositionValue(BigDecimal positionValue) {
        this.positionValue = positionValue;
    }

    public BigDecimal getDayProfit() {
        return dayProfit;
    }

    public void setDayProfit(BigDecimal dayProfit) {
        this.dayProfit = dayProfit;
    }
}
