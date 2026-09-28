package io.github.zhoutaoq.shadowflow.biz.watch;

import io.github.zhoutaoq.shadowflow.notify.support.NotifyText;

/**
 * 盯盘推送消息的<b>卡片排版</b>（v1.6.3 起，v1.6.4 改整卡染色 + 整卡跳转）：
 * 把一次告警渲染成钉钉卡片的正文。
 *
 * <p>排版口径（详见 docs/design/req5-告警消息样式改版.md）：
 * <ul>
 *   <li><b>整卡染色</b>：涨红跌绿不是靠一个小三角，而是整张卡片的文字都用方向色 ——
 *       钉钉 markdown 支持 {@code <font color>}，这里逐行包裹，
 *       数值缺失（—）的行退成中性灰（不标色）；</li>
 *   <li>标题行 = 方向标记 + 名称 + 动作短语，加粗；</li>
 *   <li>关键数字一行一条；<b>正文里不再放链接行</b> ——
 *       跳转由整卡承载（见 {@code NotifyMessage.link}）：点卡片任意位置都跳，
 *       不用在正文里挂一串候选让人挑；</li>
 *   <li>措辞分档沿用 {@link PriceWatchTask#phraseFor}，这里只管排版不改措辞。</li>
 * </ul>
 *
 * <p><b>为什么颜色写在这里而不是调用方</b>：颜色是排版的一部分，
 * 且「哪行该标色、哪行该退灰」与文案结构绑在一起，散到调用方必然走样。
 *
 * <p><b>本类是推送页（手动实时推送）与盯盘任务（自动告警）共用的唯一出口</b>，
 * 两边渲染出的卡片必须长得一模一样 —— 改排版只改这里。
 */
public final class WatchCards {

    private WatchCards() {
    }

    /** 涨（红）：国内行情约定，涨用红 */
    public static final String UP = "#E02020";
    /** 跌（绿）：国内行情约定，跌用绿 */
    public static final String DOWN = "#12A150";
    /** 中性灰：平盘、或数值缺失不知道方向时用（不标色） */
    public static final String FLAT = "#8A8F99";

    /** 方向标记（波动用三角、封板用圆点），配合整卡颜色一起表达方向 */
    public static final String UP_TRI = "🔺";
    public static final String DOWN_TRI = "🔻";
    public static final String UP_DOT = "🔴";
    public static final String DOWN_DOT = "🟢";
    /** 开板：既不是涨也不是跌的状态，用中性标记 */
    public static final String OPEN_DOT = "⭕";
    /** 平盘 */
    public static final String FLAT_DOT = "⚪";

    /**
     * 整行染色：产出的必须是 {@code <font color="#E02020">…</font>}，引号是普通双引号。
     *
     * <p><b>曾经踩过的坑</b>：这里一度写成转义引号（{@code <font color=\"…\">}），
     * 后果是钉钉把整个标签当成普通文字原样吐出来 —— 收件人看到的是一堆带斜杠的
     * 源码文本，既没有颜色也没有排版。钉钉 markdown 只认标准 HTML 属性写法，
     * 颜色值用十六进制（不认 {@code red} 这种英文名，手机上会掉色）。
     */
    public static String paint(String color, String text) {
        return "<font color=\"" + color + "\">" + text + "</font>";
    }

    /** 标题行：加粗 + 方向色（卡片正文的第一行，也是会话里最显眼的一行） */
    private static String head(String color, String text) {
        return "**" + paint(color, text) + "**";
    }

    /** 无值占位（—），按中性色渲染 —— 不知道方向就不标方向色 */
    private static String dash(String color, String text) {
        return text == null || text.trim().isEmpty() ? paint(FLAT, "—") : paint(color, text);
    }

    /**
     * 卡片正文里的换行符。
     *
     * <p><b>这里用 {@code <br>} 而不是 {@code \n}，是钉钉的硬约束</b>：钉钉 markdown
     * 只支持语法的子集，正文里的 {@code \n} 会被折叠、连续空格被并成一个，列表 {@code - }
     * 在窄屏手机上缩进错乱。只有 {@code <br>} 能保证「每条数据独占一行」。
     */
    private static final String BR = "<br>";

    /**
     * 行情快照卡片（推送页手动推送用）：不动告警口径，把当下的行情摆出来。
     *
     * @param color  整卡颜色（涨 {@link #UP} / 跌 {@link #DOWN} / 平 {@link #FLAT}）
     * @param pctText 涨跌幅文本（不含 % 号，null 显示 —）
     */
    public static String snapshot(String name, String color, String priceText, String pctText,
                                  String preCloseText, String highText, String lowText,
                                  String volText, String amtText, String timeText) {
        String dot = FLAT.equals(color) ? FLAT_DOT : (UP.equals(color) ? UP_TRI : DOWN_TRI);
        StringBuilder sb = new StringBuilder();
        sb.append(head(color, dot + " " + name + " 行情快照")).append("\n\n");
        sb.append(paint(color, "现价：" + yuan(priceText)
                + "（涨跌幅 " + pctWord(pctText) + "）")).append(BR);
        sb.append(paint(color, "昨收：" + yuan(preCloseText)
                + "　最高 " + (highText == null ? "—" : highText)
                + "　最低 " + (lowText == null ? "—" : lowText))).append(BR);
        if (volText != null || amtText != null) {
            sb.append(paint(color, "今日：" + (volText == null ? "—" : volText)
                    + " / " + (amtText == null ? "—" : amtText))).append(BR);
        }
        if (timeText != null && !timeText.trim().isEmpty()) {
            sb.append(paint(color, "行情时间：" + timeText)).append(BR);
        }
        return sb.toString();
    }

    /**
     * 波动告警卡片。
     *
     * @param up        方向（true = 涨）
     * @param action    动作短语（phraseFor 的结果，如「短线快速拉升」）
     * @param mainSec   命中规则的过去时长（秒）
     * @param spanPct   累计涨跌幅（带符号文本，不含 % 号；null 显示 —）
     * @param priceText 现价文本（两位小数，null 显示 —）
     * @param todayPct  当前涨跌幅文本（不含 % 号，null 显示 —）
     * @param volText   拉升成交量文本（无基线传 null，该行省略）
     * @param amtText   拉升成交额文本（无基线传 null，该行省略）
     */
    public static String fluctuation(String name, boolean up, String action,
                                     long mainSec, String spanPct, String priceText, String todayPct,
                                     String volText, String amtText) {
        String color = up ? UP : DOWN;
        String dot = up ? UP_TRI : DOWN_TRI;
        StringBuilder sb = new StringBuilder();
        sb.append(head(color, dot + " " + name + " " + action)).append("\n\n");
        // 累计涨跌幅与当前涨跌幅各自独立取缺失：缺哪个哪个退灰，不影响另一行
        sb.append(paint(spanPct == null ? FLAT : color,
                "过去 " + mainSec + "s：" + (up ? "涨幅 " : "跌幅 ")
                        + (spanPct == null ? "—" : pctWord(spanPct)))).append(BR);
        sb.append(paint(color, "现价：" + yuan(priceText)))
                .append(paint(todayPct == null ? FLAT : color,
                        "（当前涨跌幅 " + pctWord(todayPct) + "）")).append(BR);
        if (volText != null || amtText != null) {
            StringBuilder line = new StringBuilder("拉升");
            if (volText != null) {
                line.append("成交量：").append(volText);
                if (amtText != null) {
                    line.append(" / ");
                }
            }
            if (amtText != null) {
                line.append("成交额：").append(amtText);
            }
            sb.append(paint(color, line.toString())).append(BR);
        }
        return sb.toString();
    }

    /**
     * 封板 / 开板卡片。
     *
     * @param state  {@link PriceWatchTask#LIMIT_UP} / {@link PriceWatchTask#LIMIT_DOWN}
     * @param closed true = 刚封上板，false = 刚打开板（开板是中性事件，整卡退灰）
     */
    public static String limit(String name, int state, boolean closed, String priceText,
                               String todayPct, String windowVol, String windowAmt,
                               String totalAmt) {
        boolean up = state == PriceWatchTask.LIMIT_UP;
        String word = up
                ? (closed ? "涨停" : "打开涨停")
                : (closed ? "跌停" : "打开跌停");
        // 开板没有方向可言：红绿都不合适，整卡退中性
        String color = closed ? (up ? UP : DOWN) : FLAT;
        String dot = closed ? (up ? UP_DOT : DOWN_DOT) : OPEN_DOT;
        StringBuilder sb = new StringBuilder();
        sb.append(head(color, dot + " " + name + " " + word)).append("\n\n");
        sb.append(paint(color, "现价：" + yuan(priceText)))
                .append(paint(todayPct == null ? FLAT : color,
                        "（涨跌幅 " + pctWord(todayPct) + "）")).append(BR);
        sb.append(paint(color, "本窗口：" + (windowVol == null ? "—" : windowVol)
                + " / " + (windowAmt == null ? "—" : windowAmt))).append(BR);
        sb.append(paint(color, "总成交额：" + (totalAmt == null ? "—" : totalAmt))).append(BR);
        return sb.toString();
    }

    /**
     * 涨跌停放量卡片。
     *
     * @param timesText 放大倍数文本（如「20.0 倍」）；上区间取不到传 null
     */
    public static String surge(String name, int state, String priceText, String todayPct,
                               String curAmt, String prevAmt, String timesText, String volText) {
        boolean up = state == PriceWatchTask.LIMIT_UP;
        String color = up ? UP : DOWN;
        StringBuilder sb = new StringBuilder();
        sb.append(head(color, (up ? UP_DOT : DOWN_DOT) + " " + name + " "
                + (up ? "涨停" : "跌停") + "放量")).append("\n\n");
        StringBuilder line = new StringBuilder("本区间成交额：").append(curAmt == null ? "—" : curAmt);
        if (prevAmt != null) {
            line.append("（上区间 ").append(prevAmt);
            if (timesText != null) {
                line.append("，放大 ").append(timesText);
            }
            line.append('）');
        }
        sb.append(paint(color, line.toString())).append(BR);
        sb.append(paint(color, "本区间成交量：" + (volText == null ? "—" : volText))).append(BR);
        sb.append(paint(color, "现价：" + yuan(priceText)))
                .append(paint(todayPct == null ? FLAT : color,
                        "（涨跌幅 " + pctWord(todayPct) + "）")).append(BR);
        return sb.toString();
    }

    /**
     * 卡片标题（会话列表与通知栏透出的那行短文案）：取正文首行并洗掉排版标记。
     *
     * <p>调用方拿它填 {@code NotifyMessage.cardTitle} —— 不填的话通道只能从
     * 「前缀 + 正文」里截，会话列表就会显示成「[关键词-主机] 时间 🔺 贵州茅台 行情快照」，
     * 时间戳抢了标题的位置。
     */
    public static String titleOf(String cardMarkdown) {
        if (cardMarkdown == null || cardMarkdown.trim().isEmpty()) {
            return "";
        }
        String first = cardMarkdown.split("\\R", 2)[0];
        return NotifyText.plainHead(first, 60);
    }

    /** 金额/价格文本：null 显示 —，否则带单位「元」 */
    private static String yuan(String text) {
        return text == null || text.trim().isEmpty() ? "—" : text + " 元";
    }

    /** 涨跌幅文本：null 显示 —，否则带 % 号（负数自带负号） */
    private static String pctWord(String text) {
        return text == null || text.trim().isEmpty() ? "—" : text + "%";
    }

    /** 保留给「多标的」场景：不标方向的清单行（配置变更通知那种） */
    public static String plainLine(String text) {
        return paint(FLAT, "- " + text);
    }
}
