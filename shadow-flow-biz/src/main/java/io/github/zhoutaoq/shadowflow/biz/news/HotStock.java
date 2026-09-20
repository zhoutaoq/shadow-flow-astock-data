package io.github.zhoutaoq.shadowflow.biz.news;

import java.math.BigDecimal;

/**
 * 热门股排行里的一条。
 *
 * <p>三个平台能给到的字段并不齐，所以除了「名次 / 代码 / 名称」这三项之外都是可空的：
 * <ul>
 *   <li><b>东财换手率榜</b>名称、现价、涨跌幅、换手率都有；</li>
 *   <li><b>新浪成交额榜</b>名称、现价、涨跌幅、成交额都有，但没有热度类字段；</li>
 *   <li><b>同花顺热榜</b>有名称、涨跌幅和一个「热度值」，另外多给一组题材标签（没有现价）。</li>
 * </ul>
 * 前端按「有就渲染、没有就不占位」处理，不为了对齐字段去编造数字。
 */
public class HotStock {

    /** 名次，从 1 开始；平台自己给的名次优先，缺了按返回顺序补 */
    private int rank;
    private String code;
    private String name;
    /** 现价（元）；平台不提供时为 null */
    private BigDecimal price;
    /** 当日涨跌幅（%）；平台不提供时为 null */
    private BigDecimal changePercent;
    /** 成交额（元）；只有新浪那条榜给 */
    private BigDecimal amount;
    /**
     * 热度值：本平台自己排序用的「热门」指标，只在本平台内部可比，跨平台没有意义。
     * <ul>
     *   <li>同花顺热榜：返回体里的 rate（字符串数字，如 "594582.0"）；</li>
     *   <li>东财换手率榜：换手率（%）。</li>
     * </ul>
     * 所以单独一个字段、不拿去和别的榜比大小；表头由前端按平台分别标注。
     */
    private BigDecimal hot;
    /** 平台自带的一句话标签，如同花顺的「首板涨停」；没有则为 null */
    private String tag;

    public HotStock() {
    }

    public HotStock(int rank, String code, String name) {
        this.rank = rank;
        this.code = code;
        this.name = name;
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

    public BigDecimal getPrice() {
        return price;
    }

    public void setPrice(BigDecimal price) {
        this.price = price;
    }

    public BigDecimal getChangePercent() {
        return changePercent;
    }

    public void setChangePercent(BigDecimal changePercent) {
        this.changePercent = changePercent;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public BigDecimal getHot() {
        return hot;
    }

    public void setHot(BigDecimal hot) {
        this.hot = hot;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }
}
