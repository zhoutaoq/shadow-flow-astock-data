package com.stock.kline.account;

import java.math.BigDecimal;

/**
 * 操作记录：一次买入 / 卖出。
 *
 * <p>按需求「实时生效不落盘」：只存在于内存（每个用户一份，进程重启即清空），
 * 但每次操作<b>立即</b>作用到持仓与可用金额上，页面马上能看到结果。
 *
 * <p>之所以不落盘：操作记录是「记账流水」的雏形，真正的落盘账本应当是成交明细；
 * 当前需求只要实时效果，硬落盘反而会让「填错一条记录」变得难以撤销。
 *
 * <p>作用规则（价格一律取该标的的持仓成本价）：
 * <ul>
 *   <li>买入：持仓数量 +N；可用金额 -（成本价 × N）；标的自动加入盯盘；</li>
 *   <li>卖出：持仓数量 -N（减到 0 则移除该持仓）；可用金额 +（成本价 × N）；
 *       标的从盯盘中移除。</li>
 * </ul>
 */
public class TradeRecord {

    /** 买入 */
    public static final String ACTION_BUY = "buy";
    /** 卖出 */
    public static final String ACTION_SELL = "sell";

    private long id;
    private long userId;
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

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }
}
