package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;

/**
 * 一只标的的实时快照（决策引擎的唯一输入单位）。
 *
 * <p>字段来源分两处，别混：
 * <ul>
 *   <li><b>批量接口</b>（{@code ulist.np}）：价格、涨跌幅、量额、换手、量比、今日主力净额。
 *       一次请求能拿几十只，盘中轮询走这条；</li>
 *   <li><b>单只接口</b>（{@code stock/get}）：委比、内外盘、均价。
 *       批量接口里同样编号的字段含义不同（实测 f49/f71/f161/f191 在两边不是一个东西），
 *       所以只在需要时按单只补，不做字段复用。</li>
 * </ul>
 *
 * <p>{@link #avgPrice} 在本类里由「成交额 ÷ 成交量」自算，不依赖接口 ——
 * 实测与单只接口的均价字段完全一致，少一次请求。
 */
public class QuantQuote {

    private String code;
    private String name;

    private BigDecimal price = BigDecimal.ZERO;
    /** 涨跌幅（%） */
    private BigDecimal changePct = BigDecimal.ZERO;
    /** 涨跌额 */
    private BigDecimal changeAmt = BigDecimal.ZERO;
    /** 成交量（手） */
    private BigDecimal volume = BigDecimal.ZERO;
    /** 成交额（元） */
    private BigDecimal amount = BigDecimal.ZERO;
    /** 换手率（%） */
    private BigDecimal turnoverRate = BigDecimal.ZERO;
    /** 量比 */
    private BigDecimal volumeRatio = BigDecimal.ZERO;

    private BigDecimal high = BigDecimal.ZERO;
    private BigDecimal low = BigDecimal.ZERO;
    private BigDecimal open = BigDecimal.ZERO;
    private BigDecimal preClose = BigDecimal.ZERO;

    private BigDecimal totalMv = BigDecimal.ZERO;
    private BigDecimal floatMv = BigDecimal.ZERO;

    /** 今日主力净流入（元）：批量接口直接给，和 fflow 的日级口径一致 */
    private BigDecimal mainNetInflow = BigDecimal.ZERO;

    /** 当日均价（白线/黄线里的黄线）= 成交额 ÷ 成交量 ÷ 100 */
    private BigDecimal avgPrice = BigDecimal.ZERO;
    /** 委比（%）：只有单只接口给，未补时为 null */
    private BigDecimal wbRatio;
    /** 外盘（手） */
    private BigDecimal outerVolume;
    /** 内盘（手） */
    private BigDecimal innerVolume;

    /** 快照时刻（毫秒），用来做过期判断 */
    private long fetchedAt;

    /** 现价相对均价的偏离（%）：正 = 白线在黄线上方 */
    public BigDecimal priceVsAvgPct() {
        if (avgPrice == null || avgPrice.signum() <= 0 || price == null) {
            return BigDecimal.ZERO;
        }
        return price.subtract(avgPrice).multiply(BigDecimal.valueOf(100))
                .divide(avgPrice, 2, BigDecimal.ROUND_HALF_UP);
    }

    /** 内外盘净额（手）：外盘 - 内盘，正数表示主动性买盘占优 */
    public BigDecimal outerMinusInner() {
        if (outerVolume == null || innerVolume == null) {
            return null;
        }
        return outerVolume.subtract(innerVolume);
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

    public BigDecimal getChangePct() {
        return changePct;
    }

    public void setChangePct(BigDecimal changePct) {
        this.changePct = changePct;
    }

    public BigDecimal getChangeAmt() {
        return changeAmt;
    }

    public void setChangeAmt(BigDecimal changeAmt) {
        this.changeAmt = changeAmt;
    }

    public BigDecimal getVolume() {
        return volume;
    }

    public void setVolume(BigDecimal volume) {
        this.volume = volume;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getTurnoverRate() {
        return turnoverRate;
    }

    public void setTurnoverRate(BigDecimal turnoverRate) {
        this.turnoverRate = turnoverRate;
    }

    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }

    public void setVolumeRatio(BigDecimal volumeRatio) {
        this.volumeRatio = volumeRatio;
    }

    public BigDecimal getHigh() {
        return high;
    }

    public void setHigh(BigDecimal high) {
        this.high = high;
    }

    public BigDecimal getLow() {
        return low;
    }

    public void setLow(BigDecimal low) {
        this.low = low;
    }

    public BigDecimal getOpen() {
        return open;
    }

    public void setOpen(BigDecimal open) {
        this.open = open;
    }

    public BigDecimal getPreClose() {
        return preClose;
    }

    public void setPreClose(BigDecimal preClose) {
        this.preClose = preClose;
    }

    public BigDecimal getTotalMv() {
        return totalMv;
    }

    public void setTotalMv(BigDecimal totalMv) {
        this.totalMv = totalMv;
    }

    public BigDecimal getFloatMv() {
        return floatMv;
    }

    public void setFloatMv(BigDecimal floatMv) {
        this.floatMv = floatMv;
    }

    public BigDecimal getMainNetInflow() {
        return mainNetInflow;
    }

    public void setMainNetInflow(BigDecimal mainNetInflow) {
        this.mainNetInflow = mainNetInflow;
    }

    public BigDecimal getAvgPrice() {
        return avgPrice;
    }

    public void setAvgPrice(BigDecimal avgPrice) {
        this.avgPrice = avgPrice;
    }

    public BigDecimal getWbRatio() {
        return wbRatio;
    }

    public void setWbRatio(BigDecimal wbRatio) {
        this.wbRatio = wbRatio;
    }

    public BigDecimal getOuterVolume() {
        return outerVolume;
    }

    public void setOuterVolume(BigDecimal outerVolume) {
        this.outerVolume = outerVolume;
    }

    public BigDecimal getInnerVolume() {
        return innerVolume;
    }

    public void setInnerVolume(BigDecimal innerVolume) {
        this.innerVolume = innerVolume;
    }

    public long getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(long fetchedAt) {
        this.fetchedAt = fetchedAt;
    }
}
