package com.stock.kline.account;

import java.math.BigDecimal;

/**
 * 用户账户：一人一条，记录账户期初市值与可用金额。
 *
 * <p>口径（需求 v1.5.1 第 ③ 条）：
 * <ul>
 *   <li><b>期初市值</b> initValue —— 用户自己填的账户本金基准，总收益 = 当前总市值 - 期初市值；</li>
 *   <li><b>可用金额</b> cash —— 买入按「成本价 × 数量」扣减、卖出按「成本价 × 数量」加回。
 *       这里刻意不用成交价/现价：操作记录只记方向与数量，价格一律以持仓成本价为准，
 *       口径稳定、可复算，不会因为盘中价格跳动导致余额对不上。</li>
 * </ul>
 *
 * <p>当前总市值 = Σ(持仓数量 × 现价) + 可用金额，由 {@code AccountService} 实时算，
 * 不落盘 —— 落盘的只有期初市值与可用金额这两个「用户输入 / 变动过」的值。
 */
public class Account {

    /** 归属用户 id */
    private long userId;
    /** 账户期初市值（元） */
    private BigDecimal initValue = BigDecimal.ZERO;
    /** 可用金额（元） */
    private BigDecimal cash = BigDecimal.ZERO;
    /** 备注 */
    private String remark;
    private String createTime;
    private String updateTime;

    public long getUserId() {
        return userId;
    }

    public void setUserId(long userId) {
        this.userId = userId;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
