package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 钉钉群机器人推送（文本消息）。
 *
 * <p>安全设置用「加签」时：把 <code>timestamp + "\n" + secret</code> 用secret 做
 * HmacSHA256，再 Base64 + URL 编码，作为 <code>sign</code> 参数拼到 webhook 上。
 */
public class DingTalkNotifier {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotifier.class);

    private final WatchProperties props;

    public DingTalkNotifier(WatchProperties props) {
        this.props = props;
    }

    /**
     * 运行时覆盖 webhook（测试用它指到假机器人，避免连真实钉钉）。
     * <p>不影响 secret —— 加签所需的 secret 仍从 Spring 配置里来。
     */
    void setWebhook(String webhook) {
        props.getDingtalk().setWebhook(webhook);
    }

    /**
     * 推送一条文本消息。
     *
     * @return 是否推送成功（webhook 未配置时返回 false，由调用方决定是否记日志）
     */
    public boolean send(String content) {
        WatchProperties.DingTalk dt = props.getDingtalk();
        if (!dt.configured()) {
            return false;
        }
        try {
            String url = buildUrl(dt);
            String body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + json(content) + "}}";
            String resp = post(url, body);
            log.info("[watch] 钉钉推送返回 {}", resp);
            return true;
        } catch (Exception e) {
            log.error("[watch] 钉钉推送失败: {}", e.getMessage());
            return false;
        }
    }

    /** 拼 webhook：带上加签所需的 timestamp 与 sign */
    private String buildUrl(WatchProperties.DingTalk dt) throws Exception {
        String webhook = dt.getWebhook().trim();
        String secret = dt.getSecret() == null ? "" : dt.getSecret().trim();
        if (secret.isEmpty()) {
            return webhook;
        }
        long timestamp = System.currentTimeMillis();
        String sign = sign(timestamp, secret);
        return webhook + (webhook.contains("?") ? "&" : "?")
                + "timestamp=" + timestamp + "&sign=" + sign;
    }

    /** 加签：HmacSHA256(secret, timestamp + "\n" + secret) → Base64 → URL 编码 */
    static String sign(long timestamp, String secret) throws Exception {
        String toSign = timestamp + "\n" + secret;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] data = mac.doFinal(toSign.getBytes(StandardCharsets.UTF_8));
        return URLEncoder.encode(Base64.getEncoder().encodeToString(data), "UTF-8");
    }

    private String post(String urlStr, String body) throws IOException {
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

    /** 文本内容转 JSON 字符串（JSON 里没有转义工具，手动处理几个关键字符） */
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
