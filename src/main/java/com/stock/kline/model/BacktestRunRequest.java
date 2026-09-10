package com.stock.kline.model;

import java.math.BigDecimal;

/**
 * 发起一次回测的请求。
 *
 * <p>约定：所有比率一律用小数传递（页面按 % 录入，提交前换算）。
 * 例：手续费滑点 0.01% 传 0.0001；涨幅 5% 传 0.05。
 */
public class BacktestRunRequest {

    private String stockCode;
    private String market = "stock";
    private String startDate;
    private String endDate;
    /** 账户起始金额，必须 &gt; 0 */
    private BigDecimal initAmount = new BigDecimal("100000");
    /** 摩擦成本费率（小数），默认 0.01% = 0.0001，必须 &gt;= 0 */
    private BigDecimal feeRate = new BigDecimal("0.0001");
    private BacktestRule buyRule;
    private BacktestRule sellRule;
    /** 前端生成的任务 id，用于轮询执行进度；不传则不记录进度 */
    private String taskId;

    /**
     * 涨跌停无法成交时的处理配置。
     *
     * <p>买入卡、卖出卡各配一份，互不影响：信号日 T 买入遇涨停封板买不进、卖出遇跌停封板卖不出，
     * 两侧的容忍度通常不一样，所以分开配置。
     */
    public static class LimitConfig {

        /**
         * 处理策略：
         * <ul>
         *   <li>1 = 无视：仍按 T 日收盘价成交</li>
         *   <li>2 = 次日开盘非涨跌停立即成交</li>
         *   <li>3 = 次日开盘非涨跌停、且相对 T 日收盘价的涨跌幅满足 {@link #conds} 任一条件才成交</li>
         * </ul>
         *
         * <p>2 / 3 下：买入遇次日涨停开盘、卖出遇次日跌停开盘，一律作废不再尝试。
         */
        private int strategy = 1;

        /**
         * 策略 3 的阈值条件组合：最多 2 个、条件间为「或」(满足任意一条即成交)。
         * 为空时退回「次日开盘即成交」（等同策略 2）。
         */
        private java.util.List<LimitCond> conds;

        /** 单条阈值条件：方向 + 比较符 + 数值 */
        public static class LimitCond {

            /**
             * 涨跌方向：
             * <ul>
             *   <li>up = 涨幅：需当日上涨，且比较幅度 = 涨幅</li>
             *   <li>down = 跌幅：需当日下跌，且比较幅度 = 跌幅</li>
             * </ul>
             */
            private String dir = "up";

            /** 比较符：gt = 幅度大于 threshold；lt = 幅度小于 threshold */
            private String op = "gt";

            /** 阈值幅度（小数），例：0.03 = 3% */
            private BigDecimal ratio;

            public String getDir() {
                return dir;
            }

            public void setDir(String dir) {
                this.dir = dir;
            }

            public String getOp() {
                return op;
            }

            public void setOp(String op) {
                this.op = op;
            }

            public BigDecimal getRatio() {
                return ratio;
            }

            public void setRatio(BigDecimal ratio) {
                this.ratio = ratio;
            }
        }

        public int getStrategy() {
            return strategy;
        }

        public void setStrategy(int strategy) {
            this.strategy = strategy;
        }

        public java.util.List<LimitCond> getConds() {
            return conds;
        }

        public void setConds(java.util.List<LimitCond> conds) {
            this.conds = conds;
        }
    }

    /** 买入侧的涨跌停处理配置 */
    private LimitConfig buyLimit = new LimitConfig();
    /** 卖出侧的涨跌停处理配置 */
    private LimitConfig sellLimit = new LimitConfig();

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public BigDecimal getInitAmount() {
        return initAmount;
    }

    public void setInitAmount(BigDecimal initAmount) {
        this.initAmount = initAmount;
    }

    public BigDecimal getFeeRate() {
        return feeRate;
    }

    public void setFeeRate(BigDecimal feeRate) {
        this.feeRate = feeRate;
    }

    public BacktestRule getBuyRule() {
        return buyRule;
    }

    public void setBuyRule(BacktestRule buyRule) {
        this.buyRule = buyRule;
    }

    public BacktestRule getSellRule() {
        return sellRule;
    }

    public void setSellRule(BacktestRule sellRule) {
        this.sellRule = sellRule;
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public LimitConfig getBuyLimit() {
        return buyLimit;
    }

    public void setBuyLimit(LimitConfig buyLimit) {
        this.buyLimit = buyLimit;
    }

    public LimitConfig getSellLimit() {
        return sellLimit;
    }

    public void setSellLimit(LimitConfig sellLimit) {
        this.sellLimit = sellLimit;
    }
}
