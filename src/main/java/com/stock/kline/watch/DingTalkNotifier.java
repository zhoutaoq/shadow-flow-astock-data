package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 钉钉群机器人推送（文本消息）。
 *
 * <p>v1.4 起只支持「自定义关键词」模式：消息文本必须含钉钉机器人后台配置的关键词，
 * 才会被服务端放行；不再支持「加签」（后者要求服务端按 secret 算 sign 拼到 URL 上）。
 *
 * <p>实际推送仍是 POST 一段 JSON 到 webhook，body 形如
 * <code>{"msgtype":"text","text":{"content":"..."}}</code>。
 *
 * <p>两个入口：{@link #send(String)} 走盯盘自己的 webhook（自动补「关键词 + 公网 IP + 推送时刻」前缀），
 * {@link #post(String, String)} 是通用入口，供用户反馈等模块指定 webhook 复用。
 */
public class DingTalkNotifier {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotifier.class);

    /**
     * 消息里的时间戳口径：年月日时分秒，与项目其它模块（AuthService、各 Repository）一致。
     *
     * <p>{@link DateTimeFormatter} 不可变、线程安全，可以当常量共用 ——
     * 告警可能从 watchExecutor 的多个线程同时发，{@code SimpleDateFormat} 那种写法在这里会串时间。
     */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WatchProperties props;

    public DingTalkNotifier(WatchProperties props) {
        this.props = props;
        // 启动就先在后台把公网 IP 探出来（要发外网请求，不阻塞启动），
        // 这样第一条告警拼前缀时通常已经有缓存，不用同步等网络。
        WatchText.warmUpPublicIp();
    }

    /**
     * 运行时覆盖 webhook（测试用它指到假机器人，避免连真实钉钉）。
     */
    void setWebhook(String webhook) {
        props.getDingtalk().setWebhook(webhook);
    }

    /**
     * 推送一条盯盘文本消息：用 {@code watch.dingtalk.webhook}，自动补上
     * <code>[智能盯盘-公网IP] 2026-09-14 10:23:45 </code> 前缀。
     *
     * <p>前缀是消息信封，两样东西都是给人看的：
     * <ul>
     *   <li>「智能盯盘」满足机器人「自定义关键词」校验（钉钉按子串匹配，
     *       <code>[智能盯盘-1.2.3.4]</code> 一样能命中）；后挂的公网 IP 用来区分是哪台机器发的 ——
     *       同一套程序部署在两台以上机器、告警都进同一个群时，一眼能看出是哪台在报；
     *       探测不到公网 IP 时退回原样的 <code>[智能盯盘]</code>，不影响送达。</li>
     *   <li>IP 后面是<b>推送时刻</b>（年月日时分秒）：群里翻历史消息、事后复盘对时间，
     *       不必再去服务器上翻 app.log。取的是「发送这一刻」而不是行情时间 ——
     *       正文里的现价与涨跌幅本身就是那一刻的行情，时间戳只回答「这条什么时候触发的」。</li>
     * </ul>
     *
     * <p>盯盘的全部推送都从这里出去（超阈值告警、封板/开板、放量、关键时点快报、
     * 启动与变更通知），所以时间戳只在这一处加，5 个调用点自动全覆盖。
     *
     * @return 是否推送成功（webhook 未配置时返回 false，由调用方决定是否记日志）
     */
    public boolean send(String content) {
        return post(props.getDingtalk().getWebhook(),
                keywordTag() + " " + LocalDateTime.now().format(TS) + " " + content);
    }

    /**
     * 消息前缀：正常是 <code>[智能盯盘-1.2.3.4]</code>，公网 IP 拿不到时退成 <code>[智能盯盘]</code>。
     *
     * <p>用出口公网 IP 而不是网卡地址：云主机上公网 IP 是 NAT 出来的、网卡里只有内网地址，
     * 取网卡只会得到 10.x / 192.168.x，没有任何标识意义。
     */
    private static String keywordTag() {
        String ip = WatchText.publicIp();
        return ip == null || ip.isEmpty() ? "[智能盯盘]" : "[智能盯盘-" + ip + "]";
    }

    /**
     * 通用文本推送：把 content <b>原样</b> POST 到指定 webhook。
     *
     * <p>用户反馈等其它模块复用它，避免各自再写一遍 HTTP + JSON 转义。
     * 注意钉钉「自定义关键词」模式下消息文本必须含机器人后台配的关键词，
     * 这个前缀由调用方自己拼（盯盘那边是 {@code [智能盯盘-公网IP] 推送时刻 }，见 {@link #send(String)}）。
     *
     * @param webhook 目标机器人地址，空白视为未配置、直接返回 false
     * @return 是否推送成功（HTTP 2xx 且响应体已读取）
     */
    public boolean post(String webhook, String content) {
        // 总开关：开发环境默认关，一条都发不出去，避免本地反复启停往群里刷消息
        if (!props.getDingtalk().isEnabled()) {
            log.info("[dingtalk] 钉钉推送总开关已关闭（--watch.dingtalk.enabled=true 打开），本次不发送：{}",
                    oneLine(content));
            return false;
        }
        // 时段闸门：非交易时段（含收盘后、午休、周末）一条都不发。
        // 放在这里是因为 post() 是所有钉钉消息的唯一出口 ——
        // 盯盘告警、关键时点播报、启动通知、用户反馈全走它，一处拦截就是全部拦截。
        if (!props.getDingtalk().pushAllowedNow()) {
            log.info("[dingtalk] 非交易时段（{}），本次不发送：{}",
                    props.getDingtalk().getTradeSessions(), oneLine(content));
            return false;
        }
        if (webhook == null || webhook.trim().isEmpty()) {
            return false;
        }
        try {
            String body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + json(content) + "}}";
            String resp = postHttp(webhook.trim(), body);
            log.info("[dingtalk] 钉钉推送返回 {}", resp);
            return true;
        } catch (Exception e) {
            log.error("[dingtalk] 钉钉推送失败: {}", e.getMessage());
            return false;
        }
    }

    private String postHttp(String urlStr, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(10000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new IOException("HTTP " + status);
        }
        try (java.io.InputStream in = conn.getInputStream();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** 日志里只留一行摘要，避免整条消息刷屏 */
    private static String oneLine(String s) {
        if (s == null) {
            return "";
        }
        String v = s.replace('\n', ' ').trim();
        return v.length() <= 80 ? v : v.substring(0, 80) + "...";
    }

    /**
     * 文本内容转 JSON 字符串（JSON 里没有转义工具，手动处理几个关键字符）
     */
    static String json(String s) {
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
                    sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
