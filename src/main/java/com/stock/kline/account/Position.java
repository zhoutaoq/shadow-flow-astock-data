package com.stock.kline.account;

import java.math.BigDecimal;

/**
 * 持仓：一条 = 一只标的的成本价与持仓数量，挂在某个用户账户下。
 *
 * <p>收益口径（需求 v1.5.1 第 ③ 条）：
 * <ul>
 *   <li><b>当日收益</b> = (现价 - 昨收) × 数量；</li>
 *   <li><b>总收益</b> = (现价 - 成本价) × 数量。</li>
 * </ul>
 *
 * <p>现价与昨收在查询时用新浪实时行情现取（见 {@code AccountService}），不落盘，
 * 所以落盘字段只有标的、成本价、数量。
 */
public class Position {

    private Long id;
    /** 归属用户 id：持仓必须关联到账户上 */
    private long userId;
    /** 标的代码（6 位沪深A股） */
    private String code;
    /** 标的名称：页面录入时可留空，由名录补 */
    private String name;
    /** 成本价（元） */
    private BigDecimal costPrice = BigDecimal.ZERO;
    /** 持仓数量（股） */
    private int quantity;
    private String createTime;
    private String updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
