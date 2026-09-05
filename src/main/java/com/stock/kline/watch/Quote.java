package com.stock.kline.watch;

import java.math.BigDecimal;

/** 一次行情查询的结果快照 */
public class Quote {

    private final String code;
    private final String name;
    /** 现价（元）；停牌或未取到时为 null */
    private final BigDecimal price;
    /** 昨收（元） */
    private final BigDecimal preClose;
    /** 当日最高价（元）：现价等于它才可能封在涨停板上 */
    private final BigDecimal high;
    /** 当日最低价（元）：现价等于它才可能封在跌停板上 */
    private final BigDecimal low;
    /** 数据时间（毫秒），新浪返回的是行情快照时间 */
    private final long quoteTime;
    /** 接口返回的原始行情时间文本，如 2026-09-08 15:00:00 */
    private final String timeText;

    public Quote(String code, String name, BigDecimal price, BigDecimal preClose,
                 BigDecimal high, BigDecimal low, long quoteTime, String timeText) {
        this.code = code;
        this.name = name;
        this.price = price;
        this.preClose = preClose;
        this.high = high;
        this.low = low;
        this.quoteTime = quoteTime;
        this.timeText = timeText;
    }

    /**
     * 相对昨收的涨跌幅（%），昨收缺失或非正数时返回 null。
     */
    public BigDecimal changePercent() {
        if (price == null || preClose == null || preClose.signum() <= 0) {
            return null;
        }
        return price.subtract(preClose)
                .multiply(BigDecimal.valueOf(100))
                .divide(preClose, 2, BigDecimal.ROUND_HALF_UP);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public BigDecimal getPreClose() {
        return preClose;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public long getQuoteTime() {
        return quoteTime;
    }

    public String getTimeText() {
        return timeText;
    }
}
