package io.github.zhoutaoq.shadowflow.biz.watch;

import java.math.BigDecimal;

/**
 * 涨跌停判定规则（v1.6.8 第 ② 条）。
 *
 * <p>原规则只看<b>一根快照</b>：现价贴在当日最高价上、且涨幅达到阈值 → 涨停；贴在最低价上、
 * 跌幅达到阈值 → 跌停。问题是「贴板」这一瞬间可能只是恰好成交在最高价：
 * 盘中冲高回落时也会出现 price == high，推出去就是一条假的涨停通知。
 *
 * <p>v1.6.8 在原有条件之上<b>再加一条</b>：现价必须<b>连续三次</b>没变化，才算真的封板
 * （按轮询间隔算，10 秒一轮就是至少 20 秒没动过）。两条同时成立才判涨跌停：
 *
 * <pre>
 *   涨停 = 现价 == 最高价 && 涨幅 &gt; 阈值 && 连续同价轮数 &gt;= 3
 *   跌停 = 现价 == 最低价 && 跌幅 &lt; -阈值 && 连续同价轮数 &gt;= 3
 * </pre>
 *
 * <p>抽成纯函数是为了能脱离 Spring 单独验证（见 {@code scripts/verify_limit_rules}）：
 * 布尔逻辑散在调度类里，改一条规则就得启动整个应用才能确认对错。
 */
public final class LimitRules {

    /** 封板状态：涨停 / 未涨跌停 / 跌停 */
    public static final int UP = 1;
    public static final int NONE = 0;
    public static final int DOWN = -1;

    /** 需要连续多少轮现价不变才算封板（需求原文：连续三次成交价没变化） */
    public static final int FLAT_ROUNDS = 3;

    /** 阈值（百分比，各留 0.5% 容错）：ST 5%→4.5%，创业 30x / 科创 688 为 20%→19.5%，其余 10%→9.5% */
    private static final BigDecimal TH_ST = new BigDecimal("4.5");
    private static final BigDecimal TH_GROW = new BigDecimal("19.5");
    private static final BigDecimal TH_MAIN = new BigDecimal("9.5");

    private LimitRules() {
    }

    /** 封板阈值（百分比）：名称含 ST 是 5%，创业板 30x 与科创板 688 是 20%，其余主板 10% */
    public static BigDecimal threshold(String name, String code) {
        if (name != null && name.toUpperCase().contains("ST")) {
            return TH_ST;
        }
        String c = code == null ? "" : code.trim();
        if (c.startsWith("688") || c.startsWith("30")) {
            return TH_GROW;
        }
        return TH_MAIN;
    }

    /**
     * 连续同价轮数：与上一轮同价则 +1，否则重新从 1 开始。
     *
     * @param prevCount 上一轮的连续轮数（首轮传 0）
     * @return 本轮连续同价轮数；价格缺失返回 0（本轮不参与封板判断）
     */
    public static int nextFlatCount(BigDecimal prevPrice, BigDecimal price, int prevCount) {
        if (price == null || price.signum() <= 0) {
            return 0;
        }
        if (prevPrice != null && prevPrice.compareTo(price) == 0) {
            return Math.max(prevCount, 0) + 1;
        }
        return 1;
    }

    /**
     * 当前封板状态。
     *
     * @param flatCount 连续同价轮数（跨日要调用方自己重置，见 {@code PriceWatchTask}）
     * @return {@link #UP} / {@link #NONE} / {@link #DOWN}
     */
    public static int stateOf(BigDecimal price, BigDecimal pct, BigDecimal high, BigDecimal low,
                              BigDecimal threshold, int flatCount) {
        if (price == null || pct == null || threshold == null) {
            return NONE;
        }
        // 新增条件：没连续三次同价，一律不算封板（哪怕这一根正好贴在最值上）
        if (flatCount < FLAT_ROUNDS) {
            return NONE;
        }
        if (high != null && price.compareTo(high) == 0 && pct.compareTo(threshold) > 0) {
            return UP;
        }
        if (low != null && price.compareTo(low) == 0 && pct.compareTo(threshold.negate()) < 0) {
            return DOWN;
        }
        return NONE;
    }
}
