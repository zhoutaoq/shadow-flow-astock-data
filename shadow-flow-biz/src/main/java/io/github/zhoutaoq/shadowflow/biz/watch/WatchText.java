package io.github.zhoutaoq.shadowflow.biz.watch;

import java.math.BigDecimal;

/**
 * 盯盘相关推送的文案取值小工具，零状态。
 *
 * <p>只做「数字 → 展示文本」的格式化，避免各推送类各写一份口径。
 * 主机地址取值见 {@link io.github.zhoutaoq.shadowflow.biz.support.PublicIp}，
 * 消息前缀拼装见 {@link io.github.zhoutaoq.shadowflow.biz.support.PushEnvelope}。
 */
final class WatchText {

    private WatchText() {
    }

    /** double → 无尾零文本：1.0 → "1"，0.5 → "0.5" */
    static String pct(double d) {
        return new BigDecimal(Double.toString(d)).stripTrailingZeros().toPlainString();
    }

    /** 涨跌幅带符号：null → "—"，正值加 "+"；比率已经两位小数，这里只加符号 */
    static String signed(BigDecimal v) {
        if (v == null) {
            return "—";
        }
        return (v.signum() > 0 ? "+" : "") + v.toPlainString() + "%";
    }

    /** 现价文本：null → "—"，股价一律两位小数 */
    static String price(BigDecimal v) {
        return v == null ? "—" : v.setScale(2, BigDecimal.ROUND_HALF_UP).toPlainString();
    }
}
