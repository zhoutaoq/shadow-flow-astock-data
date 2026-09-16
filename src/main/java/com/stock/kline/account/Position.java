package com.stock.kline.account;

import java.math.BigDecimal;

/**
 * 持仓：一条 = 一只标的的成本价与持仓数量，挂在<b>某个账户</b>下（需求 v1.5.2 第 ④ 条）。
 *
 * <p>归属两层都要带：{@code userId} 用于鉴权（一行都碰不到别人的），
 * {@code accountId} 用于定位到具体账户 —— 「同一只标的一本账只能有一条持仓」这个约束
 * 是在账户维度上成立的，不同账户可以各自持有同一只标的。
 *
 * <p>收益口径（需求 v1.5.1 第 ③ 条、v1.5.5 第 ② ③ 条）：
 * <ul>
 *   <li><b>当日收益</b> = (现价 - <b>当日基准价</b>) × 数量。基准价按份额拆开：
 *       当天买入的那部分用<b>当日买入均价</b>、隔夜持仓那部分用<b>昨收</b>、
 *       页面直接配置且建仓日就是今天的整条用<b>成本价</b>；</li>
 *   <li><b>总收益</b> = (现价 - 成本价) × 数量。</li>
 * </ul>
 *
 * <p>现价与昨收在查询时用新浪实时行情现取（见 {@code AccountService}），不落盘。
 * 落盘字段因此分三类：归属与标的、成本价与数量（含 v1.5.6 的<b>可用数量</b>）、
 * 以及 v1.5.5 新增的<b>建仓日</b>与<b>当天买入池</b>（当天买入的数量与金额，
 * 它是「当天买入」的当日盈亏基准，跨天自动作废）。
 */
public class Position {

    private Long id;
    /** 归属用户 id：鉴权用 */
    private long userId;
    /** 归属账户 id：持仓挂在账户下 */
    private long accountId;
    /** 标的代码（6 位沪深A股） */
    private String code;
    /** 标的名称：页面录入时可留空，由名录补 */
    private String name;
    /** 成本价（元）：加仓时按「老成本市值 + 本次成交额」加权平均重算 */
    private BigDecimal costPrice = BigDecimal.ZERO;
    /** 持仓数量（股） */
    private int quantity;
    /**
     * 可用数量（股，需求 v1.5.6 第 ① 条）：这条持仓里<b>能卖出</b>的数量。
     *
     * <p>与「持仓数量」分开记：持仓是账面有的，可用是当下能动的（限售、当日买入待转可用、
     * 手工冻结都会让两者不一致）。卖出只能在这条线上走 —— 页面上「卖出」的候选标的与
     * 可填数量都以它为准，超出一股都不给过。
     *
     * <p><b>null = 没单独设过</b>（v1.5.6 之前的老数据 / 老前端提交），等价于「持仓数量全部可用」，
     * 这样老数据升级后照样能卖。业务侧的取值一律走 {@code AccountService#usableQty}，
     * 那里会把 null 折成持仓数量、并把越界值夹到 [0, 持仓数量]。
     */
    private Integer availableQty;
    /**
     * 建仓日 yyyy-MM-dd（需求 v1.5.5 第 ③ 条），<b>非必填</b>。
     *
     * <p>用途有两个：页面上直接展示；以及「建仓日就是今天」时整条持仓的当日盈亏
     * 直接以成本价为基准（这种持仓是页面配出来的，没有当日买入均价可用）。
     */
    private String openDate;
    /** 「当天买入池」归属日期 yyyy-MM-dd；与今天不一致即视为隔夜持仓 */
    private String dayBuyDate;
    /** 当天买入、且当前仍在持仓中的数量（股） */
    private int dayBuyQty;
    /** 当天买入部分的成交金额（元）：金额 ÷ 数量 = 当日买入均价 */
    private BigDecimal dayBuyAmount = BigDecimal.ZERO;
    private String createTime;
    private String updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
    }

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public Integer getAvailableQty() {
        return availableQty;
    }

    public void setAvailableQty(Integer availableQty) {
        this.availableQty = availableQty;
    }

    public String getOpenDate() {
        return openDate;
    }

    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    public String getDayBuyDate() {
        return dayBuyDate;
    }

    public void setDayBuyDate(String dayBuyDate) {
        this.dayBuyDate = dayBuyDate;
    }

    public int getDayBuyQty() {
        return dayBuyQty;
    }

    public void setDayBuyQty(int dayBuyQty) {
        this.dayBuyQty = dayBuyQty;
    }

    public BigDecimal getDayBuyAmount() {
        return dayBuyAmount;
    }

    public void setDayBuyAmount(BigDecimal dayBuyAmount) {
        this.dayBuyAmount = dayBuyAmount;
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
