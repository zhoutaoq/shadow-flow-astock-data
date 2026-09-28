package io.github.zhoutaoq.shadowflow.notify.channel.dingtalk;

import io.github.zhoutaoq.shadowflow.notify.api.Notifier;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyMessage;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyResult;
import io.github.zhoutaoq.shadowflow.notify.config.NotifyProperties;
import io.github.zhoutaoq.shadowflow.notify.support.NotifyText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 群机器人 Webhook 通道（文本 / Markdown）。
 *
 * <p>协议很简单：把正文包成一段 JSON，POST 到目标地址：
 * <pre>
 * 文本     {"msgtype":"text","text":{"content":"..."}}
 * Markdown {"msgtype":"markdown","markdown":{"title":"...","text":"..."}}
 * 整卡跳转 {"msgtype":"actionCard","actionCard":{"title":"...","text":"...",
 *                                                "singleTitle":"...","singleURL":"..."}}
 * </pre>
 *
 * <p><b>整卡跳转（{@link NotifyMessage#linkUrl()} 非空）时投整体跳转卡片</b>：
 * 点消息任意位置都跳那个地址 —— 这是钉钉唯一「整条消息就是一个链接」的形态，
 * 普通 markdown 只能靠正文里的行内链接，点空白区域没反应。卡片正文仍是 markdown，
 * 排版与染色都不受影响；通道不支持或没给地址时退回普通正文，跳转只是增强，
 * 不能因为没有它就不发消息。
 *
 * <p><b>本类里没有任何业务词</b>：出现的字符串只有协议层的 JSON 字段名与消息类型。
 * 消息前缀（比如满足关键词校验的标签）由调用方通过
 * {@link NotifyMessage#prefix()} 传进来。
 *
 * <p>两个刻意的实现选择：
 * <ul>
 *   <li><b>失败只返回结果、不抛异常</b>：通知失败不该把调用方的主流程带崩，
 *       调用方（如注册）自己的业务必须照常算成功。</li>
 *   <li><b>识别服务端返回的业务错误码</b>：这类机器人即使内容被拒（典型是
 *       「自定义关键词」没命中）也回 HTTP 200，只在响应体里给一个非 0 的业务码。
 *       只看 HTTP 状态会把「没送达」报成「成功」—— 那是最难查的一类故障，
 *       所以这里解析响应体，能确认失败就报失败；解析不出来则按成功处理，不误报。</li>
 * </ul>
 */
public class DingTalkWebhookNotifier implements Notifier {

    /** 通道标识 */
    public static final String CHANNEL = "dingtalk";

    private static final Logger log = LoggerFactory.getLogger(DingTalkWebhookNotifier.class);

    private final NotifyProperties props;

    public DingTalkWebhookNotifier(NotifyProperties props) {
        this.props = props;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public NotifyResult send(String endpoint, NotifyMessage message) {
        if (NotifyText.blank(endpoint)) {
            return NotifyResult.fail("未配置投递地址");
        }
        String content = message == null ? "" : message.render();
        if (NotifyText.blank(content)) {
            return NotifyResult.fail("消息内容为空");
        }
        boolean markdown = message != null && message.markdown();
        String linkUrl = message == null ? null : message.linkUrl();
        try {
            String body;
            if (!NotifyText.blank(linkUrl)) {
                body = actionCardBody(cardTitleOf(message, content), content,
                        linkTextOf(message), linkUrl.trim());
            } else {
                // 标题同样走纯文本清洗：会话列表只吃纯文本，带前缀或染色标签会露馅
                body = markdown
                        ? markdownBody(cardTitleOf(message, content), content)
                        : textBody(content);
            }
            String resp = postJson(endpoint.trim(), body);
            String err = businessError(resp);
            if (err != null) {
                log.error("[notify] 已发出但被服务端拒收（{}）：{}；内容摘要：{}",
                        channel(), err, NotifyText.oneLine(content));
                return NotifyResult.fail("服务端拒收: " + err);
            }
            log.info("[notify] 投递完成（{}），服务端返回 {}", channel(), NotifyText.oneLine(resp));
            return NotifyResult.ok();
        } catch (Exception e) {
            log.error("[notify] 投递失败（{}）: {}", channel(), e.getMessage());
            return NotifyResult.fail(e.getMessage());
        }
    }

    private static String textBody(String content) {
        return "{\"msgtype\":\"text\",\"text\":{\"content\":" + NotifyText.json(content) + "}}";
    }

    private static String markdownBody(String title, String content) {
        return "{\"msgtype\":\"markdown\",\"markdown\":{\"title\":" + NotifyText.json(title)
                + ",\"text\":" + NotifyText.json(content) + "}}";
    }

    /**
     * 整体跳转卡片：{@code singleURL} 是卡片级跳转地址，点消息任意位置都走它。
     *
     * <p>字段口径按自定义机器人的协议来（与服务端单发消息 API 的下划线写法不同）：
     * 正文用 {@code text}（内容是 markdown）、跳转条用 {@code singleTitle} + {@code singleURL}。
     * 会话列表透出的 {@code title} 只吃纯文本，所以这里先洗掉排版标记。
     *
     * <p><b>外部浏览器打开</b>：钉钉 PC 端默认在侧边栏/内嵌打开 URL，要落到外部浏览器
     * 得在地址上追加 {@code pc_slide=false}（官方约定，见消息链接说明）。这里统一拼上；
     * 移动端是钉钉的硬限制，只能在客户端内打开，无法从消息侧绕过。
     */
    private static String actionCardBody(String title, String content, String linkText, String linkUrl) {
        return "{\"msgtype\":\"actionCard\",\"actionCard\":{"
                + "\"title\":" + NotifyText.json(title)
                + ",\"text\":" + NotifyText.json(content)
                + ",\"singleTitle\":" + NotifyText.json(linkText)
                + ",\"singleURL\":" + NotifyText.json(forceExternalBrowser(linkUrl))
                + "}}";
    }

    /**
     * 给跳转地址追加 {@code pc_slide=false}，让 PC 端在外部浏览器打开。
     *
     * <p>已有 query 就用 {@code &}、没有就用 {@code ?}。钉钉只认 {@code pc_slide} 这一个
     * 官方开关（false = 外部浏览器，true = 侧边栏）。别手写别的键名，钉钉不认。
     */
    static String forceExternalBrowser(String url) {
        if (url == null || url.trim().isEmpty()) {
            return url;
        }
        String u = url.trim();
        if (u.contains("pc_slide=")) {
            return u; // 调用方已经自己控制过，不重复拼
        }
        return u + (u.contains("?") ? "&" : "?") + "pc_slide=false";
    }

    /** 卡片标题：调用方给了就用，没给就从正文首行截取（会话列表只吃纯文本） */
    private static String cardTitleOf(NotifyMessage message, String content) {
        String given = message == null ? null : message.cardTitle();
        if (!NotifyText.blank(given)) {
            return given.trim();
        }
        String first = content.split("\\R", 2)[0];
        String plain = NotifyText.plainHead(first, 64);
        return plain.isEmpty() ? "通知" : plain;
    }

    /** 跳转条文案：调用方没指定时用 {@link NotifyMessage#DEFAULT_LINK_TEXT} */
    private static String linkTextOf(NotifyMessage message) {
        String given = message == null ? null : message.linkText();
        return NotifyText.blank(given) ? NotifyMessage.DEFAULT_LINK_TEXT : given.trim();
    }

    /**
     * 从响应体里找业务错误码。
     *
     * <p>只认形如 {@code "errcode":0} 的字段；能找到且非 0 才算失败。
     * 找不到（响应被网关改写过、返回了别的格式）一律返回 null 按成功处理 ——
     * 宁可漏报也不要误报，误报会让调用方把已送达的消息当成失败去重试。
     */
    private static String businessError(String resp) {
        if (resp == null) {
            return null;
        }
        int idx = resp.indexOf("\"errcode\"");
        if (idx < 0) {
            return null;
        }
        int colon = resp.indexOf(':', idx);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < resp.length() && (resp.charAt(i) == ' ' || resp.charAt(i) == '"')) {
            i++;
        }
        int start = i;
        while (i < resp.length() && (Character.isDigit(resp.charAt(i)) || resp.charAt(i) == '-')) {
            i++;
        }
        if (i == start) {
            return null;
        }
        String code = resp.substring(start, i);
        if ("0".equals(code)) {
            return null;
        }
        int e = resp.indexOf("\"errmsg\"");
        String msg = "";
        if (e >= 0) {
            int c2 = resp.indexOf(':', e);
            int q1 = c2 < 0 ? -1 : resp.indexOf('"', c2 + 1);
            int q2 = q1 < 0 ? -1 : resp.indexOf('"', q1 + 1);
            if (q1 >= 0 && q2 > q1) {
                msg = resp.substring(q1 + 1, q2);
            }
        }
        return "errcode=" + code + (msg.isEmpty() ? "" : " " + msg);
    }

    private String postJson(String urlStr, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(props == null ? 5000 : props.getConnectTimeoutMs());
        conn.setReadTimeout(props == null ? 10000 : props.getReadTimeoutMs());
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new IOException("HTTP " + status);
        }
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            // 响应很短，读满 64KB 就停，防异常情况下拖住线程
            while (out.size() < 65536 && (n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
