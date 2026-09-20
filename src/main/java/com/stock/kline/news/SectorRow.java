package com.stock.kline.news;

import java.math.BigDecimal;

/**
 * 板块涨跌幅里的一行（行业口径）。
 *
 * <p>东财与新浪都能多给一个「领涨股」，两家字段名不同但语义一致，统一到这三个可空字段上；
 * 领涨股缺了就只渲染板块名与涨跌幅，不留空壳。
 */
public class SectorRow {

    /** 名次，从 1 开始，按涨跌幅从高到低 */
    private int rank;
    /** 板块代码：东财是 BKxxxx，新浪是 new_xxxx */
    private String code;
    private String name;
    /** 板块涨跌幅（%） */
    private BigDecimal changePercent;
    /** 领涨股名称；平台不提供时为 null */
    private String leaderName;
    /**
     * 领涨股代码（6 位，不含市场前缀）；平台不提供时为 null。
     *
     * <p>v1.5.8 加这个字段是为了让页面上的领涨股也能点开跳转 ——
     * 只有名称拼不出任何一个平台的 URL。东财给的是 f140，新浪给的是
     * 板块值串里的「sh600847 / sz002623」这种带前缀的写法，解析时剥掉前缀。
     */
    private String leaderCode;
    /** 领涨股涨跌幅（%） */
    private BigDecimal leaderChangePercent;

    public SectorRow() {
    }

    public SectorRow(int rank, String code, String name, BigDecimal changePercent) {
        this.rank = rank;
        this.code = code;
        this.name = name;
        this.changePercent = changePercent;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
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

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public String getLeaderName() {
        return leaderName;
    }

    public void setLeaderName(String leaderName) {
        this.leaderName = leaderName;
    }

    public String getLeaderCode() {
        return leaderCode;
    }

    public void setLeaderCode(String leaderCode) {
        this.leaderCode = leaderCode;
    }

    public BigDecimal getLeaderChangePercent() {
        return leaderChangePercent;
    }

    public void setLeaderChangePercent(BigDecimal leaderChangePercent) {
        this.leaderChangePercent = leaderChangePercent;
    }
}
