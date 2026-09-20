package io.github.zhoutaoq.shadowflow.biz.account;

import java.math.BigDecimal;

/**
 * 账户：一个用户可以有多本账（需求 v1.5.2 第 ④ 条，参考同花顺「一级账户、二级持仓」）。
 *
 * <p>一个账户 = 一套独立的期初市值 / 可用金额 / 持仓 / 操作记录。
 * 账户之间完全隔离：在「A 账户」买入不会影响「B 账户」的持仓与余额，
 * 盯盘名单仍然挂在用户维度（同一只标的在哪个账户里都是同一份行情）。
 *
 * <p>口径（沿用需求 v1.5.1 第 ③ 条）：
 * <ul>
 *   <li><b>期初市值</b> initValue —— 用户自己填的账户本金基准，总收益 = 当前总市值 - 期初市值；</li>
 *   <li><b>可用金额</b> cash —— 买入按「成本价 × 数量」扣减、卖出按「成本价 × 数量」加回。
 *       这里刻意不用成交价/现价：操作记录只记方向与数量，价格一律以持仓成本价为准，
 *       口径稳定、可复算，不会因为盘中价格跳动导致余额对不上。</li>
 * </ul>
 *
 * <p>当前总市值 = Σ(持仓数量 × 现价) + 可用金额，由 {@code AccountService} 实时算，
 * 不落盘 —— 落盘的只有期初市值与可用金额这两个「用户输入 / 变动过」的值。
 */
public class Account {

    /** 账户 id：同一个用户下唯一，新建账户时由仓储分配 */
    private long id;
    /** 归属用户 id */
    private long userId;
    /** 账户名称：同一用户下不重名 */
    private String name;
    /** 账户期初市值（元） */
    private BigDecimal initValue = BigDecimal.ZERO;
    /** 可用金额（元） */
    private BigDecimal cash = BigDecimal.ZERO;
    /**
     * 当日已实现盈亏的归属日期 yyyy-MM-dd（需求 v1.5.5 第 ② 条）。
     *
     * <p>当天卖出（含当天买入当天卖出）那部分已经落袋的收益：账户当日收益 =
     * 持仓浮动当日盈亏 + 这里这份已实现。与今天的日期不一致即视为过期、按 0 参与计算，
     * 所以不必专门跑定时任务归零。
     */
    private String dayRealizedDate;
    /** 当日已实现盈亏（元） */
    private BigDecimal dayRealizedProfit = BigDecimal.ZERO;
    /** 备注 */
    private String remark;
    private String createTime;
    private String updateTime;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
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

    public String getDayRealizedDate() {
        return dayRealizedDate;
    }

    public void setDayRealizedDate(String dayRealizedDate) {
        this.dayRealizedDate = dayRealizedDate;
    }

    public BigDecimal getDayRealizedProfit() {
        return dayRealizedProfit;
    }

    public void setDayRealizedProfit(BigDecimal dayRealizedProfit) {
        this.dayRealizedProfit = dayRealizedProfit;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
