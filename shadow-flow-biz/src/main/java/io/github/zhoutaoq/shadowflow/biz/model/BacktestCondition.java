package io.github.zhoutaoq.shadowflow.biz.model;

import java.math.BigDecimal;

/**
 * 回测策略的判断组件（一个组件 = 一条判断规则）。
 *
 * <p>第一版提供三类组件：
 * <ul>
 *   <li>{@link #TYPE_CHANGE} 近 n 个交易日涨幅 &gt;/&lt; x（x 为小数，可为负）</li>
 *   <li>{@link #TYPE_ABOVE_MA_DAYS} 连续 n 个交易日收盘价 &gt;/&lt; m 日线</li>
 *   <li>{@link #TYPE_CLOSE_MA} 收盘价 &gt;/&lt; n 日线</li>
 * </ul>
 *
 * <p>参数含义随 type 变化：CHANGE 用 n + x；ABOVE_MA_DAYS 用 n + m；CLOSE_MA 只用 n（n 即均线周期）。
 */
public class BacktestCondition {

    /** 近 n 个交易日涨幅（相对 n 个交易日前的收盘价） */
    public static final String TYPE_CHANGE = "CHANGE";
    /** 连续 n 个交易日收盘价都站上/跌破 m 日线 */
    public static final String TYPE_ABOVE_MA_DAYS = "ABOVE_MA_DAYS";
    /** 收盘价站上/跌破 n 日线 */
    public static final String TYPE_CLOSE_MA = "CLOSE_MA";
    /** 成交量与「前 n 日平均成交量 × 倍数」比较 */
    public static final String TYPE_VOLUME_MA = "VOLUME_MA";

    /** 大于 */
    public static final String OP_GT = "gt";
    /** 小于 */
    public static final String OP_LT = "lt";

    private String type = TYPE_CHANGE;
    private String op = OP_GT;
    /** 回溯天数 / 连续天数 / 均线周期，视 type 而定，必须 &gt;= 1 */
    private Integer n = 5;
    /** 均线周期，仅 ABOVE_MA_DAYS 使用，必须 &gt;= 1 */
    private Integer m = 20;
    /** 涨幅阈值（小数，可为负），仅 CHANGE 使用 */
    private BigDecimal x = BigDecimal.ZERO;
    /** 倍数（小数，必须 &gt; 0），仅 VOLUME_MA 使用 */
    private BigDecimal ratio = new BigDecimal("1.5");

    /** 参数是否合法，返回错误信息；合法返回 null */
    public String validate() {
        if (!TYPE_CHANGE.equals(type) && !TYPE_ABOVE_MA_DAYS.equals(type)
                && !TYPE_CLOSE_MA.equals(type) && !TYPE_VOLUME_MA.equals(type)) {
            return "未知的策略组件类型: " + type;
        }
        if (!OP_GT.equals(op) && !OP_LT.equals(op)) {
            return "未知的比较方向: " + op;
        }
        if (n == null || n < 1) {
            return "天数 n 必须 >= 1";
        }
        if (TYPE_ABOVE_MA_DAYS.equals(type) && (m == null || m < 1)) {
            return "均线周期 m 必须 >= 1";
        }
        if (TYPE_CHANGE.equals(type) && x == null) {
            return "涨幅阈值 x 不能为空";
        }
        if (TYPE_VOLUME_MA.equals(type)
                && (ratio == null || ratio.compareTo(BigDecimal.ZERO) <= 0)) {
            return "成交量倍数必须大于 0";
        }
        return null;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getOp() {
        return op;
    }

    public void setOp(String op) {
        this.op = op;
    }

    public Integer getN() {
        return n;
    }

    public void setN(Integer n) {
        this.n = n;
    }

    public Integer getM() {
        return m;
    }

    public void setM(Integer m) {
        this.m = m;
    }

    public BigDecimal getX() {
        return x;
    }

    public void setX(BigDecimal x) {
        this.x = x;
    }

    public BigDecimal getRatio() {
        return ratio;
    }

    public void setRatio(BigDecimal ratio) {
        this.ratio = ratio;
    }
}
