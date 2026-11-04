package io.github.zhoutaoq.shadowflow.biz.quant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 大盘环境：所有个股信号共用的那部分判断（权重 20%，但实际是<b>总开关</b>）。
 *
 * <p>为什么不把它摊进个股指标里加权：大盘破位时，个股所有支撑位都失效 ——
 * 这种关系是「乘数」不是「加数」。所以这里先算出一个 0-100 的环境分与一个布尔闸门，
 * 再交给决策引擎决定「今天要不要参与」。
 *
 * <p>口径与需求表一致：点位涨跌、距 52 周低、市场宽度（涨跌家数、涨跌停、板块资金流）。
 */
public class MarketContext {

    /** 上证 / 深成 / 创业板涨跌幅（%） */
    private BigDecimal shChangePct = BigDecimal.ZERO;
    private BigDecimal szChangePct = BigDecimal.ZERO;
    private BigDecimal cybChangePct = BigDecimal.ZERO;
    /** 上证距 52 周低点的百分比：越小越危险，< 2% 一律轻仓 */
    private BigDecimal shDistanceToLowPct = BigDecimal.ZERO;

    private int upCount;
    private int downCount;
    private int limitUpCount;
    private int limitDownCount;
    /** 行业板块里主力净流入为正的个数 / 取样总数 */
    private int sectorInflowCount;
    private int sectorTotal;

    /** 环境分 0-100 */
    private int score = 50;
    /** 闸门：false 时当天不产生任何买入信号 */
    private boolean pass = true;
    /** 判断依据，一条一句，直接进决策日志与页面 */
    private List<String> notes = new ArrayList<>();

    public BigDecimal getShChangePct() {
        return shChangePct;
    }

    public void setShChangePct(BigDecimal shChangePct) {
        this.shChangePct = shChangePct;
    }

    public BigDecimal getSzChangePct() {
        return szChangePct;
    }

    public void setSzChangePct(BigDecimal szChangePct) {
        this.szChangePct = szChangePct;
    }

    public BigDecimal getCybChangePct() {
        return cybChangePct;
    }

    public void setCybChangePct(BigDecimal cybChangePct) {
        this.cybChangePct = cybChangePct;
    }

    public BigDecimal getShDistanceToLowPct() {
        return shDistanceToLowPct;
    }

    public void setShDistanceToLowPct(BigDecimal shDistanceToLowPct) {
        this.shDistanceToLowPct = shDistanceToLowPct;
    }

    public int getUpCount() {
        return upCount;
    }

    public void setUpCount(int upCount) {
        this.upCount = upCount;
    }

    public int getDownCount() {
        return downCount;
    }

    public void setDownCount(int downCount) {
        this.downCount = downCount;
    }

    public int getLimitUpCount() {
        return limitUpCount;
    }

    public void setLimitUpCount(int limitUpCount) {
        this.limitUpCount = limitUpCount;
    }

    public int getLimitDownCount() {
        return limitDownCount;
    }

    public void setLimitDownCount(int limitDownCount) {
        this.limitDownCount = limitDownCount;
    }

    public int getSectorInflowCount() {
        return sectorInflowCount;
    }

    public void setSectorInflowCount(int sectorInflowCount) {
        this.sectorInflowCount = sectorInflowCount;
    }

    public int getSectorTotal() {
        return sectorTotal;
    }

    public void setSectorTotal(int sectorTotal) {
        this.sectorTotal = sectorTotal;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public boolean isPass() {
        return pass;
    }

    public void setPass(boolean pass) {
        this.pass = pass;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = notes;
    }
}
