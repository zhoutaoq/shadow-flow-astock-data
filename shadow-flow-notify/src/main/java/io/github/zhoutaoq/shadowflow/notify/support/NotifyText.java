package io.github.zhoutaoq.shadowflow.notify.support;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 通知相关的通用文本小工具，零状态、无依赖。
 *
 * <p>这里只放「任何通道都用得上、且与业务无关」的字符串处理：
 * JSON 转义、日志单行化、时间戳、关键词标签拼装。
 * 业务文案的拼接不在这里 —— 那是调用方的事。
 */
public final class NotifyText {

    /**
     * 消息时间戳口径：年月日时分秒。
     *
     * <p>{@link DateTimeFormatter} 不可变、线程安全，可以当常量共用 ——
     * 通知常从多个线程并发发出，{@code SimpleDateFormat} 那种写法在这里会串时间。
     */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private NotifyText() {
    }

    /** 当前时刻的默认文本形式（秒级） */
    public static String now() {
        return LocalDateTime.now().format(TS);
    }

    /** 指定时刻的默认文本形式 */
    public static String stamp(LocalDateTime time) {
        return time == null ? "" : time.format(TS);
    }

    /**
     * 关键词标签：{@code [标签]} 或 {@code [标签-主机]}。
     *
     * <p>用途是满足群机器人「自定义关键词」模式的校验 —— 这类机器人只放行
     * 文本里能搜到指定关键词的消息。<b>标签文字由调用方给</b>，SDK 不认识它；
     * 主机标识可选，用来区分「同一套程序部署在多台机器、消息都进同一个群」时是谁发的。
     *
     * @param tag  标签文字；空白则返回空串（不加标签）
     * @param host 主机标识（可为公网 IP、实例名等）；空白则退成 {@code [标签]}
     */
    public static String keywordTag(String tag, String host) {
        if (tag == null || tag.trim().isEmpty()) {
            return "";
        }
        String t = tag.trim();
        if (host == null || host.trim().isEmpty()) {
            return "[" + t + "]";
        }
        return "[" + t + "-" + host.trim() + "]";
    }

    /**
     * 文本内容转 JSON 字符串字面量。
     *
     * <p>只处理 JSON 规范要求转义的几个字符，够用于「把一段文本塞进 JSON 的某个字段」。
     */
    public static String json(String s) {
        if (s == null) {
            return "\"\"";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    // 控制字符必须转义，否则部分服务端会拒绝整个报文
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.append('"').toString();
    }

    /** 日志里只留一行摘要，避免整条消息刷屏 */
    public static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() <= 80 ? v : v.substring(0, 80) + "...";
    }

    /**
     * 地址打码，供日志使用：只保留主机与路径首段，隐藏 query。
     *
     * <p>投递地址通常自带凭据（长度很长的 access_token 之类），
     * 明文进日志等于把它写进了任何能看到日志的地方。
     */
    public static String maskEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return "(empty)";
        }
        String e = endpoint.trim();
        int q = e.indexOf('?');
        String prefix = q < 0 ? e : e.substring(0, q);
        return prefix.length() <= 60 ? prefix + (q < 0 ? "" : "?***") : prefix.substring(0, 60) + "...?***";
    }

    /**
     * 把富文本正文洗成纯文本：给「只吃纯文本」的位置（会话列表透出的标题、
     * 通知栏摘要、日志）从 markdown 正文里取一段能读的话。
     *
     * <p>处理范围是几种通用标记：标题井号、加粗星号、列表短横、{@code [文字](地址)}
     * 形式的链接（留文字去地址）、以及 {@code <font ...>} 染色标签（含手机端
     * 那种带转义引号的写法）。洗完只留文字本身，不保留任何排版意图。
     *
     * <p>这是「尽力而为」的清洗：认不出的标记原样留下，总比把内容删没了强。
     */
    public static String plainOf(String s) {
        if (s == null) {
            return "";
        }
        String v = s
                .replaceAll("(?i)</?font[^>]*>", "")
                .replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1")
                .replace("**", "")
                .replaceAll("(?m)^\\s*#{1,6}\\s*", "")
                .replaceAll("(?m)^\\s*[-*]\\s*", "")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .replaceAll("\\s{2,}", " ")
                .trim();
        return v;
    }

    /** {@link #plainOf(String)} 后截取前 max 个字符（超出加省略号），用于有长度上限的字段 */
    public static String plainHead(String s, int max) {
        String v = plainOf(s);
        if (max <= 0 || v.length() <= max) {
            return v;
        }
        return v.substring(0, max) + "...";
    }

    public static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
