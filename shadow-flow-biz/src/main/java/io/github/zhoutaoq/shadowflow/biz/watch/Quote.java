package io.github.zhoutaoq.shadowflow.biz.watch;

import java.math.BigDecimal;

/**
 * 一次行情查询的结果快照。
 *
 * <p>字段顺序对应新浪 hq.sinajs.cn 返回体（GBK 编码），从响应里按固定下标取。
 */
public class Quote {

    /** 股价统一保留两位小数：新浪原样返回三位（如 12.345），展示与推送一律先规整 */
    private static final int PRICE_SCALE = 2;

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
    /** 累计成交量（股）：与上一轮的差值就是「拉升成交量」 */
    private final BigDecimal volume;
    /** 累计成交额（元）：与上一轮的差值就是「拉升成交额」 */
    private final BigDecimal amount;
    /** 数据时间（毫秒），新浪返回的是行情快照时间 */
    private final long quoteTime;
    /** 接口返回的原始行情时间文本，如 2026-09-08 15:00:00 */
    private final String timeText;
    /**
     * {@link #price} 是不是「拿昨收顶上的」。
     *
     * <p>只有估值链路（{@code SinaQuoteClient#fetchForValuation}）会置 true：
     * 集合竞价前 / 停牌时新浪的现价字段是 0，若照旧返回 null，账户市值就会退回成本价 ——
     * 那正是用户反馈的错处。盯盘链路不置位，语义仍是「没有有效现价」。
     */
    private final boolean priceFromPreClose;

    public Quote(String code, String name, BigDecimal price, BigDecimal preClose,
                 BigDecimal high, BigDecimal low, BigDecimal volume, BigDecimal amount,
                 long quoteTime, String timeText) {
        this(code, name, price, preClose, high, low, volume, amount, quoteTime, timeText, false);
    }

    public Quote(String code, String name, BigDecimal price, BigDecimal preClose,
                 BigDecimal high, BigDecimal low, BigDecimal volume, BigDecimal amount,
                 long quoteTime, String timeText, boolean priceFromPreClose) {
        this.code = code;
        this.name = name;
        this.price = price;
        this.preClose = preClose;
        this.high = high;
        this.low = low;
        this.volume = volume;
        this.amount = amount;
        this.quoteTime = quoteTime;
        this.timeText = timeText;
        this.priceFromPreClose = priceFromPreClose;
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

    /**
     * 价格文本，固定两位小数（null 返回 null 由调用方决定怎么显示）。
     *
     * <p>推送与页面都走这里，免得各处 toPlainString 出来三位小数。
     */
    public String priceText() {
        return scale(price);
    }

    /** 昨收文本，固定两位小数 */
    public String preCloseText() {
        return scale(preClose);
    }

    /** 最高文本，固定两位小数 */
    public String highText() {
        return scale(high);
    }

    /** 最低文本，固定两位小数 */
    public String lowText() {
        return scale(low);
    }

    /** 涨跌幅文本（不带 % 号），保留两位小数；取不到返回 null */
    public String changePercentText() {
        return scale(changePercent());
    }

    /** 按 {@link #PRICE_SCALE} 位四舍五入成文本；null 原样返回 null */
    private static String scale(BigDecimal v) {
        return v == null ? null : v.setScale(PRICE_SCALE, BigDecimal.ROUND_HALF_UP).toPlainString();
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

    public BigDecimal getVolume() {
        return volume;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public long getQuoteTime() {
        return quoteTime;
    }

    public String getTimeText() {
        return timeText;
    }

    /** {@link #getPrice()} 是否取自昨收（未开盘 / 停牌时的估值口径） */
    public boolean isPriceFromPreClose() {
        return priceFromPreClose;
    }
}
