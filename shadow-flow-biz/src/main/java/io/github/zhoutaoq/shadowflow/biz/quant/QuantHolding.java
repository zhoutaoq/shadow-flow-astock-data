package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;

/**
 * 量化策略的一条持仓（需求 v2.0 第 3、9 条）。
 *
 * <p><b>可用数量单独记，是因为 T+1</b>：今天买的今天不能卖。用 {@link #availableQty} 卡住，
 * 而不是「判断 buyDate 是不是今天再推算」—— 后者在跨日重启、补数据时容易算错，
 * 落一个明确的数量更稳。每天开盘前由 {@code QuantInitTask} 把可用数量对齐到持仓数量。
 *
 * <p>{@link #peakPrice} 是移动止盈的基准：从建仓起记录见过的最高价，
 * 现价从最高价回撤超过策略参数就卖。买入当天初始化为成本价。
 */
public class QuantHolding {

    private Long id;
    private long strategyId;
    /** 归属用户 id：鉴权用，避免只靠 strategyId 反查 */
    private long userId;
    private String code;
    private String name;

    /** 持仓数量（股） */
    private int quantity;
    /** 成本价（元）：加仓按加权平均重算 */
    private BigDecimal costPrice = BigDecimal.ZERO;
    /** 可用数量（股）：T+1 下当天买入的份额次日才可卖 */
    private int availableQty;
    /** 建仓日 yyyy-MM-dd */
    private String openDate;
    /** 最近一次买入日：用来判断哪些份额是今天买的 */
    private String lastBuyDate;
    /** 建仓以来见过的最高价，移动止盈用 */
    private BigDecimal peakPrice = BigDecimal.ZERO;

    private String createTime;
    private String updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
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

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getCostPrice() {
        return costPrice;
    }

    public void setCostPrice(BigDecimal costPrice) {
        this.costPrice = costPrice;
    }

    public int getAvailableQty() {
        return availableQty;
    }

    public void setAvailableQty(int availableQty) {
        this.availableQty = availableQty;
    }

    public String getOpenDate() {
        return openDate;
    }

    public void setOpenDate(String openDate) {
        this.openDate = openDate;
    }

    public String getLastBuyDate() {
        return lastBuyDate;
    }

    public void setLastBuyDate(String lastBuyDate) {
        this.lastBuyDate = lastBuyDate;
    }

    public BigDecimal getPeakPrice() {
        return peakPrice;
    }

    public void setPeakPrice(BigDecimal peakPrice) {
        this.peakPrice = peakPrice;
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
