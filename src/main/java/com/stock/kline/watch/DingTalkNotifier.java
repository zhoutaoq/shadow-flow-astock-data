package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 钉钉群机器人推送（文本消息）。
 *
 * <p>v1.4 起只支持「自定义关键词」模式：消息文本必须含钉钉机器人后台配置的关键词，
 * 才会被服务端放行；不再支持「加签」（后者要求服务端按 secret 算 sign 拼到 URL 上）。
 *
 * <p>实际推送仍是 POST 一段 JSON 到 webhook，body 形如
 * <code>{"msgtype":"text","text":{"content":"..."}}</code>。
 */
public class DingTalkNotifier {

    private static final Logger log = LoggerFactory.getLogger(DingTalkNotifier.class);

    private final WatchProperties props;

    public DingTalkNotifier(WatchProperties props) {
        this.props = props;
    }

    /**
     * 运行时覆盖 webhook（测试用它指到假机器人，避免连真实钉钉）。
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
        content = "[智能盯盘] " + content;
        WatchProperties.DingTalk dt = props.getDingtalk();
        if (!dt.configured()) {
            return false;
        }
        try {
            String url = dt.getWebhook().trim();
            String body = "{\"msgtype\":\"text\",\"text\":{\"content\":" + json(content) + "}}";
            String resp = post(url, body);
            log.info("[watch] 钉钉推送返回 {}", resp);
            return true;
        } catch (Exception e) {
            log.error("[watch] 钉钉推送失败: {}", e.getMessage());
            return false;
        }
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
