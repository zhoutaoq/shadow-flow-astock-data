package io.github.zhoutaoq.shadowflow.biz.model;

import java.math.BigDecimal;

/** waizao_backtest_trade 回测交易明细（每次成交一条） */
public class BacktestTrade {

    /** 买入 */
    public static final String ACTION_BUY = "buy";
    /** 卖出 */
    public static final String ACTION_SELL = "sell";

    private Long id;
    private Long backtestId;
    private String tradeDate;
    private String action;
    /** 成交股数，100 的整数倍 */
    private int shares;
    /** 成交价（信号日收盘价） */
    private BigDecimal price;
    /** 成交额 = 股数 × 成交价 */
    private BigDecimal turnover;
    /** 摩擦成本 = 成交额 × 费率 */
    private BigDecimal fee;
    /** 成交后现金余额 */
    private BigDecimal cashAfter;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBacktestId() {
        return backtestId;
    }

    public void setBacktestId(Long backtestId) {
        this.backtestId = backtestId;
    }

    public String getTradeDate() {
        return tradeDate;
    }

    public void setTradeDate(String tradeDate) {
        this.tradeDate = tradeDate;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public int getShares() {
        return shares;
    }

    public void setShares(int shares) {
        this.shares = shares;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
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

    public BigDecimal getCashAfter() {
        return cashAfter;
    }

    public void setCashAfter(BigDecimal cashAfter) {
        this.cashAfter = cashAfter;
    }
}
