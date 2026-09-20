package io.github.zhoutaoq.shadowflow.biz.account;

import java.math.BigDecimal;

/**
 * 操作记录：一次买入 / 卖出，记在<b>某个账户</b>下（需求 v1.5.2 第 ④ 条）。
 *
 * <p><b>v1.5.6 起落盘</b>：与账户、持仓一起持久化（无数据库模式落同一个
 * {@code account-{userId}.json}，数据库模式落 {@code shadowflow_trade_record}），
 * 进程重启不再清空；每次操作仍然<b>立即</b>作用到该账户的持仓与可用金额上，页面马上能看到结果。
 *
 * <p>作用规则（价格取<b>实际成交价</b>，v1.5.5 起）：
 * <ul>
 *   <li>买入：持仓数量 +N、可用数量 +N，成本价按加权平均重算；可用金额 -（成交价 × N）；
 *       标的自动加入盯盘；勾了「当天」则这笔量进「当天买入池」；</li>
 *   <li>卖出：持仓数量 -N、可用数量 -N（减到 0 则移除该持仓）；可用金额 +（成交价 × N）；
 *       这一笔的当日已实现盈亏计入账户；标的在所有账户里都不再持有时才移出盯盘。</li>
 * </ul>
 *
 * <p>v1.5.4 及以前买卖一律按持仓成本价动可用金额，卖出价恒等于成本价、
 * 已实现盈亏恒为 0，当日盈亏算不出卖出那部分 —— v1.5.5 一并改掉。
 *
 * <p><b>v1.5.6 卖出守门</b>：只能卖该账户里真实持有、且<b>可用数量 > 0</b>的标的，
 * 数量不得超过可用数量 —— 这两道闸在页面（候选只列持仓）与后端（{@code AccountService}）
 * 各拦一次，页面被绕过也卖不出不存在的持仓。
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
    /**
     * 成交日期 yyyy-MM-dd（需求 v1.5.6 第 ② 条）：录入时默认今天，可回填历史成交。
     *
     * <p>与 {@link #time}（这一笔的录入时刻）分开：日期决定这笔算不算「今天的成交」——
     * 是今天才动「当天买入池」和账户的「当日已实现盈亏」；填成过去的日期只改持仓与可用金额，
     * 不动这两样当日口径（详见 {@code AccountService#addRecord}）。
     */
    private String tradeDate;
    /** 操作时间 yyyy-MM-dd HH:mm:ss（录入时刻） */
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

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
