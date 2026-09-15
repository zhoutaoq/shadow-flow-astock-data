package com.stock.kline.account;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户视图：账户设置 + 持仓明细 + 实时收益的合成结果，直接给页面用。
 *
 * <p>所有金额都是"实时算出来的"，不落盘：
 * <ul>
 *   <li>持仓市值 = Σ(数量 × 现价)；</li>
 *   <li>当前总市值 = 持仓市值 + 可用金额；</li>
 *   <li>总收益 = 当前总市值 - 期初市值；</li>
 *   <li>当日收益 = Σ((现价 - 昨收) × 数量)。</li>
 * </ul>
 */
public class AccountSummary {

    /** 账户期初市值（元） */
    private BigDecimal initValue = BigDecimal.ZERO;
    /** 可用金额（元） */
    private BigDecimal cash = BigDecimal.ZERO;
    /** 持仓市值（元）：Σ(数量 × 现价) */
    private BigDecimal positionValue = BigDecimal.ZERO;
    /** 持仓成本（元）：Σ(数量 × 成本价) */
    private BigDecimal positionCost = BigDecimal.ZERO;
    /** 当前总市值（元）= 持仓市值 + 可用金额 */
    private BigDecimal totalValue = BigDecimal.ZERO;
    /** 持仓浮动收益（元）：Σ((现价 - 成本价) × 数量) */
    private BigDecimal positionProfit = BigDecimal.ZERO;
    /** 总收益（元）= 当前总市值 - 期初市值 */
    private BigDecimal totalProfit = BigDecimal.ZERO;
    /** 总收益率（%）：期初市值为 0 时为 null */
    private BigDecimal totalProfitPercent;
    /** 当日收益（元）= Σ((现价 - 昨收) × 数量) */
    private BigDecimal dayProfit = BigDecimal.ZERO;
    /** 当日收益率（%）：按昨收市值算，拿不到昨收时为 null */
    private BigDecimal dayProfitPercent;
    private String remark;
    /** 持仓明细（含现价、当日/总收益） */
    private List<PositionView> positions = new ArrayList<>();

    /** 一条持仓的展示视图 */
    public static class PositionView {
        private Long id;
        private String code;
        private String name;
        private BigDecimal costPrice = BigDecimal.ZERO;
        private int quantity;
        /** 现价（元）：行情取不到时回落到成本价，保证页面不是空的 */
        private BigDecimal price = BigDecimal.ZERO;
        /** 昨收（元）：取不到为 null */
        private BigDecimal preClose;
        /** 成本市值 = 成本价 × 数量 */
        private BigDecimal costValue = BigDecimal.ZERO;
        /** 当前市值 = 现价 × 数量 */
        private BigDecimal marketValue = BigDecimal.ZERO;
        /** 当日收益 = (现价 - 昨收) × 数量；昨收取不到为 null */
        private BigDecimal dayProfit;
        /** 当日收益率（%）= (现价 - 昨收) / 昨收 × 100；昨收取不到为 null */
        private BigDecimal dayProfitPercent;
        /** 总收益 = (现价 - 成本价) × 数量 */
        private BigDecimal totalProfit = BigDecimal.ZERO;
        /** 总收益率（%）= (现价 - 成本价) / 成本价 × 100 */
        private BigDecimal totalProfitPercent = BigDecimal.ZERO;
        /** 是否拿到了实时行情（取不到时页面要提示"现价以成本价兜底"） */
        private boolean quoted;
        /** 行情时间文本，如 2026-09-15 15:00:00 */
        private String quoteTime;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
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

        public BigDecimal getCostPrice() {
            return costPrice;
        }

        public void setCostPrice(BigDecimal costPrice) {
            this.costPrice = costPrice;
        }

        public int getQuantity() {
            return quantity;
        }

        public void setQuantity(int quantity) {
            this.quantity = quantity;
        }

        public BigDecimal getPrice() {
            return price;
        }

        public void setPrice(BigDecimal price) {
            this.price = price;
        }

        public BigDecimal getPreClose() {
            return preClose;
        }

        public void setPreClose(BigDecimal preClose) {
            this.preClose = preClose;
        }

        public BigDecimal getCostValue() {
            return costValue;
        }

        public void setCostValue(BigDecimal costValue) {
            this.costValue = costValue;
        }

        public BigDecimal getMarketValue() {
            return marketValue;
        }

        public void setMarketValue(BigDecimal marketValue) {
            this.marketValue = marketValue;
        }

        public BigDecimal getDayProfit() {
            return dayProfit;
        }

        public void setDayProfit(BigDecimal dayProfit) {
            this.dayProfit = dayProfit;
        }

        public BigDecimal getDayProfitPercent() {
            return dayProfitPercent;
        }

        public void setDayProfitPercent(BigDecimal dayProfitPercent) {
            this.dayProfitPercent = dayProfitPercent;
        }

        public BigDecimal getTotalProfit() {
            return totalProfit;
        }

        public void setTotalProfit(BigDecimal totalProfit) {
            this.totalProfit = totalProfit;
        }

        public BigDecimal getTotalProfitPercent() {
            return totalProfitPercent;
        }

        public void setTotalProfitPercent(BigDecimal totalProfitPercent) {
            this.totalProfitPercent = totalProfitPercent;
        }

        public boolean isQuoted() {
            return quoted;
        }

        public void setQuoted(boolean quoted) {
            this.quoted = quoted;
        }

        public String getQuoteTime() {
            return quoteTime;
        }

        public void setQuoteTime(String quoteTime) {
            this.quoteTime = quoteTime;
        }
    }

    public BigDecimal getInitValue() {
        return initValue;
    }

    public void setInitValue(BigDecimal initValue) {
        this.initValue = initValue;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(BigDecimal cash) {
        this.cash = cash;
    }

    public BigDecimal getPositionValue() {
        return positionValue;
    }

    public void setPositionValue(BigDecimal positionValue) {
        this.positionValue = positionValue;
    }

    public BigDecimal getPositionCost() {
        return positionCost;
    }

    public void setPositionCost(BigDecimal positionCost) {
        this.positionCost = positionCost;
    }

    public BigDecimal getTotalValue() {
        return totalValue;
    }

    public void setTotalValue(BigDecimal totalValue) {
        this.totalValue = totalValue;
    }

    public BigDecimal getPositionProfit() {
        return positionProfit;
    }

    public void setPositionProfit(BigDecimal positionProfit) {
        this.positionProfit = positionProfit;
    }

    public BigDecimal getTotalProfit() {
        return totalProfit;
    }

    public void setTotalProfit(BigDecimal totalProfit) {
        this.totalProfit = totalProfit;
    }

    public BigDecimal getTotalProfitPercent() {
        return totalProfitPercent;
    }

    public void setTotalProfitPercent(BigDecimal totalProfitPercent) {
        this.totalProfitPercent = totalProfitPercent;
    }

    public BigDecimal getDayProfit() {
        return dayProfit;
    }

    public void setDayProfit(BigDecimal dayProfit) {
        this.dayProfit = dayProfit;
    }

    public BigDecimal getDayProfitPercent() {
        return dayProfitPercent;
    }

    public void setDayProfitPercent(BigDecimal dayProfitPercent) {
        this.dayProfitPercent = dayProfitPercent;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<PositionView> getPositions() {
        return positions;
    }

    public void setPositions(List<PositionView> positions) {
        this.positions = positions;
    }
}
