package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;

/**
 * 一笔模拟成交（需求 v2.0 第 3 条：逐笔盈亏落盘）。
 *
 * <p>与「我的账户」的 {@code TradeRecord} 不同：那边是人手工记的、价格取成本价，
 * 这边是程序按实时价撮合的，所以要带 {@link #reason}（这一笔为什么下）和
 * {@link #score}（下单时的综合分）—— 事后回看时，没有这两样就没法判断
 * 是策略参数不对，还是当时数据不对。
 *
 * <p>费用口径沿用回测：{@code 成交额 × feeRate}，买卖双边都收，默认 0.0001。
 * 印花税等 A 股特有成本不单列，统一折进费率，避免两套口径对不上。
 */
public class QuantTrade {

    public static final String ACTION_BUY = "buy";
    public static final String ACTION_SELL = "sell";

    private long id;
    private long strategyId;
    private long userId;
    /** buy / sell */
    private String action;
    private String code;
    private String name;

    /** 成交价（元）：取撮合时刻的现价 */
    private BigDecimal price = BigDecimal.ZERO;
    /** 成交数量（股）：100 的整数倍 */
    private int quantity;
    /** 成交额 = 价格 × 数量 */
    private BigDecimal turnover = BigDecimal.ZERO;
    /** 费用 = 成交额 × 费率 */
    private BigDecimal fee = BigDecimal.ZERO;
    /** 卖出这一笔的已实现盈亏 =（成交价 - 成本价）× 数量 - 费用；买入恒为 0 */
    private BigDecimal realizedPnl = BigDecimal.ZERO;
    /** 成交后可用资金 */
    private BigDecimal cashAfter = BigDecimal.ZERO;

    /** 下单时的综合分（0-100），卖出记录填当时的决策分或空 */
    private BigDecimal score;
    /** 决策原因，一句话，多条用分号连接 */
    private String reason;

    /** 成交日 yyyy-MM-dd：T+1 判定与每日汇总都按它 */
    private String tradeDate;
    /** 成交时刻 yyyy-MM-dd HH:mm:ss */
    private String time;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public long getStrategyId() {
        return strategyId;
    }

    public void setStrategyId(long strategyId) {
        this.strategyId = strategyId;
    }

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
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

    public BigDecimal getFee() {
        return fee;
    }

    public void setFee(BigDecimal fee) {
        this.fee = fee;
    }

    public BigDecimal getRealizedPnl() {
        return realizedPnl;
    }

    public void setRealizedPnl(BigDecimal realizedPnl) {
        this.realizedPnl = realizedPnl;
    }

    public BigDecimal getCashAfter() {
        return cashAfter;
    }

    public void setCashAfter(BigDecimal cashAfter) {
        this.cashAfter = cashAfter;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
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
