package com.stock.kline.account;

import java.math.BigDecimal;

/**
 * 操作记录：一次买入 / 卖出，记在<b>某个账户</b>下（需求 v1.5.2 第 ④ 条）。
 *
 * <p>按需求「实时生效不落盘」：只存在于内存（每个账户一份，进程重启即清空），
 * 但每次操作<b>立即</b>作用到该账户的持仓与可用金额上，页面马上能看到结果。
 *
 * <p>之所以不落盘：操作记录是「记账流水」的雏形，真正的落盘账本应当是成交明细；
 * 当前需求只要实时效果，硬落盘反而会让「填错一条记录」变得难以撤销。
 *
 * <p>作用规则（价格取<b>实际成交价</b>，v1.5.5 起）：
 * <ul>
 *   <li>买入：持仓数量 +N，成本价按加权平均重算；可用金额 -（成交价 × N）；
 *       标的自动加入盯盘；勾了「当天」则这笔量进「当天买入池」；</li>
 *   <li>卖出：持仓数量 -N（减到 0 则移除该持仓）；可用金额 +（成交价 × N）；
 *       这一笔的当日已实现盈亏计入账户；标的在所有账户里都不再持有时才移出盯盘。</li>
 * </ul>
 *
 * <p>v1.5.4 及以前买卖一律按持仓成本价动可用金额，卖出价恒等于成本价、
 * 已实现盈亏恒为 0，当日盈亏算不出卖出那部分 —— v1.5.5 一并改掉。
 */
public class TradeRecord {

    /** 买入 */
    public static final String ACTION_BUY = "buy";
    /** 卖出 */
    public static final String ACTION_SELL = "sell";

    private long id;
    private long userId;
    /** 归属账户 id */
    private long accountId;
    /** buy / sell */
    private String action;
    private String code;
    private String name;
    /** 成交价（元）：取操作时该标的的成本价 */
    private BigDecimal price = BigDecimal.ZERO;
    /** 数量（股） */
    private int quantity;
    /** 成交额 = 价格 × 数量（元） */
    private BigDecimal turnover = BigDecimal.ZERO;
    /** 操作后可用金额（元）：让流水自带余额快照，便于核对 */
    private BigDecimal cashAfter = BigDecimal.ZERO;
    /**
     * 是否「当天」成交（v1.5.5 第 ② 条）。
     *
     * <p>买入勾选 = 这笔量进「当天买入池」，当日盈亏从买入价起算而不是昨收；
     * 卖出勾选 = 卖的是当天买入的那部分，已实现盈亏按当日买入均价算。
     */
    private boolean sameDay = true;
    /** 卖出这一笔的当日已实现盈亏（元）：买入恒为 0 */
    private BigDecimal realizedProfit = BigDecimal.ZERO;
    /** 操作时间 yyyy-MM-dd HH:mm:ss */
    private String time;

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

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTurnover() {
        return turnover;
    }

    public void setTurnover(BigDecimal turnover) {
        this.turnover = turnover;
    }

    public BigDecimal getCashAfter() {
        return cashAfter;
    }

    public void setCashAfter(BigDecimal cashAfter) {
        this.cashAfter = cashAfter;
    }

    public boolean isSameDay() {
        return sameDay;
    }

    public void setSameDay(boolean sameDay) {
        this.sameDay = sameDay;
    }

    public BigDecimal getRealizedProfit() {
        return realizedProfit;
    }

    public void setRealizedProfit(BigDecimal realizedProfit) {
        this.realizedProfit = realizedProfit;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
