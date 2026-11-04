package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 一只标的在某一刻的完整指标与判定（决策引擎的输入与输出都在这）。
 *
 * <p><b>三项结构不是权重，是三层</b>（对应需求第 3 条的权重表）：
 * <ul>
 *   <li>{@link #l1Pass} —— 大盘 / 主力 / 位置。任一不过就否决，
 *       <b>后面的分数再高也不买</b>。用加权会把「基本面 90 分」拿来补偿「大盘破位」；</li>
 *   <li>{@link #l2Score} —— 量能 / 均价线 / 委比内外盘。决定「什么价位做」；</li>
 *   <li>{@link #totalScore} —— 十二项加权总分，只用于排序与门槛比较，不能反过来否决 L1。</li>
 * </ul>
 *
 * <p>每个分项都是 0-100。取不到数据的分项按 50（中性）处理并在 {@link #notes} 里留一句，
 * 不要拿 0 —— 0 会被当成「极差」，把一次网络抖动变成一次错误决策。
 */
public class QuantMetrics {

    private String code;
    private String name;
    private BigDecimal price = BigDecimal.ZERO;
    private BigDecimal changePct = BigDecimal.ZERO;

    /* ==================== 分项得分（0-100） ==================== */

    /** 大盘环境 20% */
    private int marketScore = 50;
    /** 主力资金 18% */
    private int mainScore = 50;
    /** 位置 15% */
    private int positionScore = 50;
    /** 量能 10% */
    private int volumeScore = 50;
    /** 均价线 8% */
    private int avgScore = 50;
    /** 委比 + 内外盘 7% */
    private int orderScore = 50;
    /** 价格形态 6% */
    private int patternScore = 50;
    /** 技术指标 5% */
    private int techScore = 50;
    /** 区间涨跌 5% */
    private int rangeScore = 50;
    /** 基本面 3% */
    private int fundamentalScore = 50;
    /** 机构预期 2% */
    private int estimateScore = 50;
    /** 评分体系 1%（自建，按前十一项折算） */
    private int scoreSystem = 50;

    /** 十二项加权总分 */
    private BigDecimal totalScore = BigDecimal.ZERO;

    /* ==================== 三层判定 ==================== */

    /** 第一层：能不能做。false 表示当天不产生买入信号 */
    private boolean l1Pass = true;
    /** L1 未通过的具体原因，一条一句 */
    private List<String> l1Fails = new ArrayList<>();
    /** 第二层：什么价位（0-100），决定买点与是否追高 */
    private int l2Score = 50;

    /* ==================== 原始值（页面展示 + 止损计算） ==================== */

    private BigDecimal todayMain = BigDecimal.ZERO;
    private BigDecimal main5d = BigDecimal.ZERO;
    private BigDecimal main10d = BigDecimal.ZERO;
    private BigDecimal main20d = BigDecimal.ZERO;
    private BigDecimal todayJumbo = BigDecimal.ZERO;

    private BigDecimal volumeRatio = BigDecimal.ZERO;
    private BigDecimal turnoverRate = BigDecimal.ZERO;
    private BigDecimal amount = BigDecimal.ZERO;
    private BigDecimal avgPrice = BigDecimal.ZERO;
    private BigDecimal wbRatio;
    private BigDecimal outerInnerDiff;

    private BigDecimal distanceTo52wHighPct = BigDecimal.ZERO;
    private BigDecimal distanceTo52wLowPct = BigDecimal.ZERO;
    private BigDecimal distanceToPrevHighPct = BigDecimal.ZERO;

    private BigDecimal chg5d = BigDecimal.ZERO;
    private BigDecimal chg10d = BigDecimal.ZERO;
    private BigDecimal chg20d = BigDecimal.ZERO;
    private BigDecimal chg60d = BigDecimal.ZERO;

    private BigDecimal macdBar = BigDecimal.ZERO;
    private BigDecimal atr = BigDecimal.ZERO;

    /** 建议止损价 = 现价 - 2×ATR，夹在成本价下方 3%~10% 之间 */
    private BigDecimal stopPrice = BigDecimal.ZERO;

    /** 判断依据，一条一句 */
    private List<String> notes = new ArrayList<>();

    /**
     * 按需求表的权重算总分。
     *
     * <p>权重写死在这里而不是做成可配：这张表是交易逻辑本身，
     * 让用户改权重等于开放「自己给自己定规则」，回测与实盘就失去可比性。
     * 可调的是阈值（最低分、止损止盈），不是权重。
     */
    public BigDecimal computeTotal() {
        double sum = marketScore * 0.20
                + mainScore * 0.18
                + positionScore * 0.15
                + volumeScore * 0.10
                + avgScore * 0.08
                + orderScore * 0.07
                + patternScore * 0.06
                + techScore * 0.05
                + rangeScore * 0.05
                + fundamentalScore * 0.03
                + estimateScore * 0.02
                + scoreSystem * 0.01;
        totalScore = BigDecimal.valueOf(sum).setScale(2, BigDecimal.ROUND_HALF_UP);
        return totalScore;
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

    public int getMarketScore() {
        return marketScore;
    }

    public void setMarketScore(int marketScore) {
        this.marketScore = marketScore;
    }

    public int getMainScore() {
        return mainScore;
    }

    public void setMainScore(int mainScore) {
        this.mainScore = mainScore;
    }

    public int getPositionScore() {
        return positionScore;
    }

    public void setPositionScore(int positionScore) {
        this.positionScore = positionScore;
    }

    public int getVolumeScore() {
        return volumeScore;
    }

    public void setVolumeScore(int volumeScore) {
        this.volumeScore = volumeScore;
    }

    public int getAvgScore() {
        return avgScore;
    }

    public void setAvgScore(int avgScore) {
        this.avgScore = avgScore;
    }

    public int getOrderScore() {
        return orderScore;
    }

    public void setOrderScore(int orderScore) {
        this.orderScore = orderScore;
    }

    public int getPatternScore() {
        return patternScore;
    }

    public void setPatternScore(int patternScore) {
        this.patternScore = patternScore;
    }

    public int getTechScore() {
        return techScore;
    }

    public void setTechScore(int techScore) {
        this.techScore = techScore;
    }

    public int getRangeScore() {
        return rangeScore;
    }

    public void setRangeScore(int rangeScore) {
        this.rangeScore = rangeScore;
    }

    public int getFundamentalScore() {
        return fundamentalScore;
    }

    public void setFundamentalScore(int fundamentalScore) {
        this.fundamentalScore = fundamentalScore;
    }

    public int getEstimateScore() {
        return estimateScore;
    }

    public void setEstimateScore(int estimateScore) {
        this.estimateScore = estimateScore;
    }

    public int getScoreSystem() {
        return scoreSystem;
    }

    public void setScoreSystem(int scoreSystem) {
        this.scoreSystem = scoreSystem;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
    }

    public boolean isL1Pass() {
        return l1Pass;
    }

    public void setL1Pass(boolean l1Pass) {
        this.l1Pass = l1Pass;
    }

    public List<String> getL1Fails() {
        return l1Fails;
    }

    public void setL1Fails(List<String> l1Fails) {
        this.l1Fails = l1Fails;
    }

    public int getL2Score() {
        return l2Score;
    }

    public void setL2Score(int l2Score) {
        this.l2Score = l2Score;
    }

    public BigDecimal getTodayMain() {
        return todayMain;
    }

    public void setTodayMain(BigDecimal todayMain) {
        this.todayMain = todayMain;
    }

    public BigDecimal getMain5d() {
        return main5d;
    }

    public void setMain5d(BigDecimal main5d) {
        this.main5d = main5d;
    }

    public BigDecimal getMain10d() {
        return main10d;
    }

    public void setMain10d(BigDecimal main10d) {
        this.main10d = main10d;
    }

    public BigDecimal getMain20d() {
        return main20d;
    }

    public void setMain20d(BigDecimal main20d) {
        this.main20d = main20d;
    }

    public BigDecimal getTodayJumbo() {
        return todayJumbo;
    }

    public void setTodayJumbo(BigDecimal todayJumbo) {
        this.todayJumbo = todayJumbo;
    }

    public BigDecimal getVolumeRatio() {
        return volumeRatio;
    }

    public void setVolumeRatio(BigDecimal volumeRatio) {
        this.volumeRatio = volumeRatio;
    }

    public BigDecimal getTurnoverRate() {
        return turnoverRate;
    }

    public void setTurnoverRate(BigDecimal turnoverRate) {
        this.turnoverRate = turnoverRate;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
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

    public BigDecimal getOuterInnerDiff() {
        return outerInnerDiff;
    }

    public void setOuterInnerDiff(BigDecimal outerInnerDiff) {
        this.outerInnerDiff = outerInnerDiff;
    }

    public BigDecimal getDistanceTo52wHighPct() {
        return distanceTo52wHighPct;
    }

    public void setDistanceTo52wHighPct(BigDecimal distanceTo52wHighPct) {
        this.distanceTo52wHighPct = distanceTo52wHighPct;
    }

    public BigDecimal getDistanceTo52wLowPct() {
        return distanceTo52wLowPct;
    }

    public void setDistanceTo52wLowPct(BigDecimal distanceTo52wLowPct) {
        this.distanceTo52wLowPct = distanceTo52wLowPct;
    }

    public BigDecimal getDistanceToPrevHighPct() {
        return distanceToPrevHighPct;
    }

    public void setDistanceToPrevHighPct(BigDecimal distanceToPrevHighPct) {
        this.distanceToPrevHighPct = distanceToPrevHighPct;
    }

    public BigDecimal getChg5d() {
        return chg5d;
    }

    public void setChg5d(BigDecimal chg5d) {
        this.chg5d = chg5d;
    }

    public BigDecimal getChg10d() {
        return chg10d;
    }

    public void setChg10d(BigDecimal chg10d) {
        this.chg10d = chg10d;
    }

    public BigDecimal getChg20d() {
        return chg20d;
    }

    public void setChg20d(BigDecimal chg20d) {
        this.chg20d = chg20d;
    }

    public BigDecimal getChg60d() {
        return chg60d;
    }

    public void setChg60d(BigDecimal chg60d) {
        this.chg60d = chg60d;
    }

    public BigDecimal getMacdBar() {
        return macdBar;
    }

    public void setMacdBar(BigDecimal macdBar) {
        this.macdBar = macdBar;
    }

    public BigDecimal getAtr() {
        return atr;
    }

    public void setAtr(BigDecimal atr) {
        this.atr = atr;
    }

    public BigDecimal getStopPrice() {
        return stopPrice;
    }

    public void setStopPrice(BigDecimal stopPrice) {
        this.stopPrice = stopPrice;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }
}
