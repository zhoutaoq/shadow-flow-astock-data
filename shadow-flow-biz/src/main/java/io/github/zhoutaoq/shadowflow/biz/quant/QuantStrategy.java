package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;

/**
 * 量化策略：一本独立的模拟账户 + 一套交易规则（需求 v2.0 第 1、2、9 条）。
 *
 * <p>与「我的账户」的区别：那边是手工记账、不管买卖点；这边由程序按指标自己决策，
 * 人只负责设定标的集合与参数。所以两者是两套数据，互不影响 ——
 * 量化策略亏了不会动到手工账户的余额，反之亦然。
 *
 * <p><b>参数分「生效」与「待生效」两份</b>（需求第 5 条：标的变化与参数调整都在次日 0 点刷新，
 * 不实时生效）。理由很实际：盘中改阈值会让同一天出现两套判断口径，回看当天交易记录时
 * 根本说不清那笔是按哪套参数下的。所以页面保存只写 {@link #pendingJson}，
 * 由 {@code QuantInitTask} 在次日 0 点合并进生效字段并清空待生效区。
 *
 * <p>资金也只动 {@link #cash}：初始资金在创建时定死，之后只能通过买卖改变可用余额，
 * 改初始资金等于重开一本账（要清持仓与流水），这一点在服务层拦。
 */
public class QuantStrategy {

    private long id;
    /** 归属用户 id：所有读写都带它，杜绝越权 */
    private long userId;
    /** 策略名称：同一用户下不重名 */
    private String name;
    private String remark;
    /** 是否启用：停用后不再为它跑决策，已有持仓保留（不会自动清仓，避免停用即砸盘） */
    private boolean enabled = true;

    /** 初始资金（元）：默认 10 万，可改 1 万 ~ 1000 万 */
    private BigDecimal initCapital = BigDecimal.valueOf(100_000);
    /** 可用资金（元） */
    private BigDecimal cash = BigDecimal.valueOf(100_000);

    /* ==================== 生效参数 ==================== */

    /** 监控标的集合，逗号分隔的 6 位代码 */
    private String watchCodes = "";

    /** 买入最低综合分（0-100） */
    private BigDecimal minScore = BigDecimal.valueOf(60);
    /** 最多同时持有几只（需求第 9 条：最多 5 只） */
    private int maxHoldings = 5;
    /** 单只最大仓位占「总资产」的比例（%），默认 20 = 五只满仓 */
    private BigDecimal maxPositionPct = BigDecimal.valueOf(20);
    /** 止损线（%）：成本价跌到这个幅度就卖 */
    private BigDecimal stopLossPct = BigDecimal.valueOf(5);
    /** 止盈线（%）：成本价涨到这个幅度就卖 */
    private BigDecimal takeProfitPct = BigDecimal.valueOf(15);
    /** 移动止盈回撤（%）：持仓浮盈超过止盈线的一半后，从最高价回撤这么多就走 */
    private BigDecimal trailDrawdownPct = BigDecimal.valueOf(8);
    /** 决策间隔（秒）：盘中每隔多久跑一轮 */
    private int decisionIntervalSec = 60;

    /* ==================== 买入硬门槛（v1.9.11） ==================== */
    /*
     * 综合分决定「谁排前面」，下面这些决定「能不能买」—— 分数再高，量比没到、主力在流出，
     * 也不该出手。每一项都对应 QuantMetrics 里已经算好的原始值，不额外取数。
     */

    /** 量比下限：低于这个数说明今天没人气 */
    private BigDecimal minVolumeRatio = BigDecimal.valueOf(1.0);
    /** 今日主力净流入下限（万元），允许填负数放宽 */
    private BigDecimal minMainInflow = BigDecimal.ZERO;
    /** 5 日主力净流入下限（万元） */
    private BigDecimal minMainInflow5d = BigDecimal.ZERO;
    /** 是否要求 MACD 金叉（柱线在零轴上方）；false = 不看这一项 */
    private boolean requireMacdGolden = false;
    /** 委比下限（%）：盘口买盘至少要有这么多 */
    private BigDecimal minWbRatio = BigDecimal.valueOf(-10);
    /** 现价离当日均价的最大偏离（%）：超过就是追高 */
    private BigDecimal maxDeviationPct = BigDecimal.valueOf(3);
    /**
     * 委差下限（手）：买五档合计减卖五档合计。
     *
     * <p>默认 0 = 买盘不能比卖盘薄。填负数放宽，填正数要求明显的买盘优势。
     * 用「手」而不是「股」，因为盘口挂单习惯按手看（1 手 = 100 股）。
     */
    private BigDecimal minWeiCha = BigDecimal.ZERO;
    /**
     * 分时放量倍数下限：当前分钟量 ÷ 前 5 分钟均量。
     *
     * <p>默认 1.2 = 至少看到 20% 的放量；填 1 或 0 等于不看。
     */
    private BigDecimal minVolSurge = BigDecimal.valueOf(1.2);

    /* ==================== 分时卖出信号（v1.9.14） ==================== */

    /** 放量滞涨：分时量能到这个倍数、涨幅却没超过 volStallMaxPct 时，按出货处理 */
    private BigDecimal volStallSurge = BigDecimal.valueOf(2.0);
    /** 放量滞涨的涨幅上限（%）：超过这个涨幅说明确实在涨，不算滞涨 */
    private BigDecimal volStallMaxPct = BigDecimal.valueOf(1.0);
    /** 委差转负阈值（手）：卖盘比买盘厚这么多就减仓；填很大的负数等于不看 */
    private BigDecimal exitWeiCha = BigDecimal.valueOf(-500);

    /* ==================== 待生效 ==================== */

    /**
     * 待生效变更的 JSON，形如 {@code {"codes":"600584,000001","params":{"minScore":65,"maxHoldings":5}}}。
     *
     * <p>null / 空串表示没有待生效变更。只在 0 点被消费一次，消费完置空 ——
     * 这样「保存后又改回来」不会留下一个永远等不到 0 点的脏状态。
     */
    private String pendingJson;

    private String createTime;
    private String updateTime;

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public BigDecimal getInitCapital() {
        return initCapital;
    }

    public void setInitCapital(BigDecimal initCapital) {
        this.initCapital = initCapital;
    }

    public BigDecimal getCash() {
        return cash;
    }

    public void setCash(BigDecimal cash) {
        this.cash = cash;
    }

    public String getWatchCodes() {
        return watchCodes;
    }

    public void setWatchCodes(String watchCodes) {
        this.watchCodes = watchCodes;
    }

    public BigDecimal getMinScore() {
        return minScore;
    }

    public void setMinScore(BigDecimal minScore) {
        this.minScore = minScore;
    }

    public int getMaxHoldings() {
        return maxHoldings;
    }

    public void setMaxHoldings(int maxHoldings) {
        this.maxHoldings = maxHoldings;
    }

    public BigDecimal getMaxPositionPct() {
        return maxPositionPct;
    }

    public void setMaxPositionPct(BigDecimal maxPositionPct) {
        this.maxPositionPct = maxPositionPct;
    }

    public BigDecimal getStopLossPct() {
        return stopLossPct;
    }

    public void setStopLossPct(BigDecimal stopLossPct) {
        this.stopLossPct = stopLossPct;
    }

    public BigDecimal getTakeProfitPct() {
        return takeProfitPct;
    }

    public void setTakeProfitPct(BigDecimal takeProfitPct) {
        this.takeProfitPct = takeProfitPct;
    }

    public BigDecimal getTrailDrawdownPct() {
        return trailDrawdownPct;
    }

    public void setTrailDrawdownPct(BigDecimal trailDrawdownPct) {
        this.trailDrawdownPct = trailDrawdownPct;
    }

    public BigDecimal getMinVolumeRatio() {
        return minVolumeRatio;
    }

    public void setMinVolumeRatio(BigDecimal minVolumeRatio) {
        this.minVolumeRatio = minVolumeRatio;
    }

    public BigDecimal getMinMainInflow() {
        return minMainInflow;
    }

    public void setMinMainInflow(BigDecimal minMainInflow) {
        this.minMainInflow = minMainInflow;
    }

    public BigDecimal getMinMainInflow5d() {
        return minMainInflow5d;
    }

    public void setMinMainInflow5d(BigDecimal minMainInflow5d) {
        this.minMainInflow5d = minMainInflow5d;
    }

    public boolean isRequireMacdGolden() {
        return requireMacdGolden;
    }

    public void setRequireMacdGolden(boolean requireMacdGolden) {
        this.requireMacdGolden = requireMacdGolden;
    }

    public BigDecimal getMinWbRatio() {
        return minWbRatio;
    }

    public void setMinWbRatio(BigDecimal minWbRatio) {
        this.minWbRatio = minWbRatio;
    }

    public BigDecimal getMinVolSurge() {
        return minVolSurge;
    }

    public void setMinVolSurge(BigDecimal minVolSurge) {
        this.minVolSurge = minVolSurge;
    }

    public BigDecimal getVolStallSurge() {
        return volStallSurge;
    }

    public void setVolStallSurge(BigDecimal volStallSurge) {
        this.volStallSurge = volStallSurge;
    }

    public BigDecimal getVolStallMaxPct() {
        return volStallMaxPct;
    }

    public void setVolStallMaxPct(BigDecimal volStallMaxPct) {
        this.volStallMaxPct = volStallMaxPct;
    }

    public BigDecimal getExitWeiCha() {
        return exitWeiCha;
    }

    public void setExitWeiCha(BigDecimal exitWeiCha) {
        this.exitWeiCha = exitWeiCha;
    }

    public BigDecimal getMinWeiCha() {
        return minWeiCha;
    }

    public void setMinWeiCha(BigDecimal minWeiCha) {
        this.minWeiCha = minWeiCha;
    }

    public BigDecimal getMaxDeviationPct() {
        return maxDeviationPct;
    }

    public void setMaxDeviationPct(BigDecimal maxDeviationPct) {
        this.maxDeviationPct = maxDeviationPct;
    }

    public int getDecisionIntervalSec() {
        return decisionIntervalSec;
    }

    public void setDecisionIntervalSec(int decisionIntervalSec) {
        this.decisionIntervalSec = decisionIntervalSec;
    }

    public String getPendingJson() {
        return pendingJson;
    }

    public void setPendingJson(String pendingJson) {
        this.pendingJson = pendingJson;
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
