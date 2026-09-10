package com.stock.kline.market;

import java.math.BigDecimal;

/**
 * 一个大盘指数的实时快照。
 *
 * <p>涨跌幅由服务端按 (现价 - 昨收) / 昨收 算好，
 * 前端只负责按正负上红绿，不重复计算，口径统一。
 */
public class MarketIndex {

    private String code;
    private String name;
    /** 当前点数 */
    private BigDecimal point;
    /** 涨跌额 */
    private BigDecimal change;
    /** 涨跌幅（%） */
    private BigDecimal changePercent;
    /** 成交额（元） */
    private BigDecimal amount;

    public MarketIndex() {
    }

    public MarketIndex(String code, String name, BigDecimal point,
                       BigDecimal change, BigDecimal changePercent, BigDecimal amount) {
        this.code = code;
        this.name = name;
        this.point = point;
        this.change = change;
        this.changePercent = changePercent;
        this.amount = amount;
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

    public BigDecimal getPoint() {
        return point;
    }

    public void setPoint(BigDecimal point) {
        this.point = point;
    }

    public BigDecimal getChange() {
        return change;
    }

    public void setChange(BigDecimal change) {
        this.change = change;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
