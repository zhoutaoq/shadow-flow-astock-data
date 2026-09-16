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
 *   <li>当日收益 = 持仓浮动当日盈亏 + 当日已实现盈亏。</li>
 * </ul>
 */
public class AccountSummary {

    /** 账户 id（二级视图据此确认看的是哪本账） */
    private long accountId;
    /** 账户名称 */
    private String accountName;
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
    /**
     * 当日收益（元）= 持仓浮动当日盈亏 + 当日已实现盈亏（需求 v1.5.5 第 ② 条）。
     *
     * <p>v1.5.4 之前这里一律是 Σ((现价 - 昨收) × 数量)，当天买入的份额被拿昨收当基准、
     * 当天卖出的已实现完全没算，所以是错的。
     */
    private BigDecimal dayProfit = BigDecimal.ZERO;
    /** 当日浮动盈亏（元）：仍持有的份额按各自基准价算出来的部分 */
    private BigDecimal dayFloatProfit = BigDecimal.ZERO;
    /** 当日已实现盈亏（元）：当天卖出部分落袋的部分（在途时可能为 0） */
    private BigDecimal dayRealizedProfit = BigDecimal.ZERO;
    /** 当日收益率（%）：按当日基准市值算，基准价缺失时为 null */
    private BigDecimal dayProfitPercent;
    private String remark;
    /** 持仓明细（含现价、当日/总收益） */
    private List<PositionView> positions = new ArrayList<>();

    /** 一条持仓的展示视图 */
    public static class PositionView {
        private Long id;
        /** 归属账户 id：首页的合计视图里把各本账拼在一起，靠它对上号 */
        private long accountId;
        /** 归属账户名称 */
        private String accountName;
        private String code;
        private String name;
        private BigDecimal costPrice = BigDecimal.ZERO;
        private int quantity;
        /**
         * 可用数量（股，v1.5.6）：这条持仓里能卖出的数量，卖出上限。
         *
         * <p>这里是<b>折算过</b>的值 —— 老数据里没单独设过时等于持仓数量，
         * 页面直接拿来用即可，不必再判空。
         */
        private int availableQty;
        /** 建仓日 yyyy-MM-dd，非必填，没填为 null */
        private String openDate;
        /** 当天买入、且仍在持仓中的数量（股）：这部分当日盈亏从当日买入均价起算 */
        private int dayBuyQty;
        /** 当日买入均价（元）：dayBuyQty > 0 时才有值 */
        private BigDecimal dayBuyAvgPrice;
        /** 当日盈亏的比较基准价（元）：按基准口径取 昨收 / 当日买入均价 / 成本价 */
        private BigDecimal dayBasisPrice;
        /** 基准价口径说明，页面直接展示：昨收 / 当日买入均价 / 建仓成本价 / 混合 */
        private String dayBasis;
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

        public long getAccountId() {
            return accountId;
        }

        public void setAccountId(long accountId) {
            this.accountId = accountId;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
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

        public int getDayBuyQty() {
            return dayBuyQty;
        }

        public void setDayBuyQty(int dayBuyQty) {
            this.dayBuyQty = dayBuyQty;
        }

        public BigDecimal getDayBuyAvgPrice() {
            return dayBuyAvgPrice;
        }

        public void setDayBuyAvgPrice(BigDecimal dayBuyAvgPrice) {
            this.dayBuyAvgPrice = dayBuyAvgPrice;
        }

        public BigDecimal getDayBasisPrice() {
            return dayBasisPrice;
        }

        public void setDayBasisPrice(BigDecimal dayBasisPrice) {
            this.dayBasisPrice = dayBasisPrice;
        }

        public String getDayBasis() {
            return dayBasis;
        }

        public void setDayBasis(String dayBasis) {
            this.dayBasis = dayBasis;
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

    public long getAccountId() {
        return accountId;
    }

    public void setAccountId(long accountId) {
        this.accountId = accountId;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
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

    public BigDecimal getDayFloatProfit() {
        return dayFloatProfit;
    }

    public void setDayFloatProfit(BigDecimal dayFloatProfit) {
        this.dayFloatProfit = dayFloatProfit;
    }

    public BigDecimal getDayRealizedProfit() {
        return dayRealizedProfit;
    }

    public void setDayRealizedProfit(BigDecimal dayRealizedProfit) {
        this.dayRealizedProfit = dayRealizedProfit;
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
