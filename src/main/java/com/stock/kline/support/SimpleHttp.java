package com.stock.kline.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 极简 GET 取文本：给「免费公开行情接口」这类既不带认证、又各家编码不一的场景用。
 *
 * <p>存在的意义只有一条：<b>把字符集猜对</b>。几家的编码实测是乱的 ——
 * 新浪 <code>hq.sinajs.cn</code> 是 GBK，新浪 <code>vip.stock…</code> 那几个 json 接口是 UTF-8，
 * 同花顺 <code>dq.10jqka.com.cn</code> 也是 UTF-8，而东财是 UTF-8。写死任何一个都会让另一家
 * 的中文变成乱码（实测把同花顺按 GBK 解出来是 <code>éé¼äºè</code> 这种）。
 *
 * <p>所以这里按「响应头 charset → UTF-8 → GBK」的顺序取第一个能解出正常文本的：
 * 先信任服务端自己声明的编码；没声明就按 UTF-8 试，解出替换字符（U+FFFD）说明猜错，
 * 再退回 GBK。两个都解不干净就用 UTF-8 的结果，至少不抛异常。
 *
 * <p>不引 HttpClient / OkHttp：本项目只用 JDK8 自带的 {@link HttpURLConnection}，
 * 与 {@code MarketIndexService}、{@code SinaQuoteClient} 保持一致，少一个依赖。
 */
public final class SimpleHttp {

    /** 各家都有防盗链，不带 Referer 会被拒或返回空体，所以 Referer 是必填参数 */
    private static final int DEFAULT_TIMEOUT_MS = 3_000;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    private static final Pattern CHARSET_IN_HEADER =
            Pattern.compile("charset\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private SimpleHttp() {
    }

    /**
     * GET 一个 URL 并解成文本。
     *
     * @param urlStr 完整地址（含 query）
     * @param referer 防盗链用的 Referer；没有就传 null
     * @return 响应体文本
     * @throws IOException 建连失败、非 2xx、读超时
     */
    public static String get(String urlStr, String referer) throws IOException {
        return get(urlStr, referer, DEFAULT_TIMEOUT_MS);
    }

    public static String get(String urlStr, String referer, int timeoutMs) throws IOException {
        return send("GET", urlStr, referer, null, null, timeoutMs);
    }

    /**
     * POST 一个 JSON 请求体。
     *
     * <p>东财的人气榜只认 POST（<code>emappdata.eastmoney.com/stockrank/getAllCurrentList</code>），
     * 换成 GET 或空体会被拒，所以这条路径必须单独支持。
     */
    public static String postJson(String urlStr, String referer, String jsonBody) throws IOException {
        return send("POST", urlStr, referer, jsonBody, "application/json", DEFAULT_TIMEOUT_MS);
    }

    private static String send(String method, String urlStr, String referer, String body,
                               String contentType, int timeoutMs) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        if (referer != null) {
            conn.setRequestProperty("Referer", referer);
        }
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", contentType + "; charset=UTF-8");
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (java.io.OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }
        }
        int status = conn.getResponseCode();
        if (status >= 400) {
            conn.disconnect();
            throw new IOException("HTTP " + status);
        }
        // Content-Type 要在 disconnect 之前取：断开后连接对象上的响应头就取不到了
        String respType = conn.getContentType();
        byte[] bytes;
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            bytes = out.toByteArray();
        } finally {
            conn.disconnect();
        }
        return decode(bytes, respType);
    }

    /**
     * 按「响应头 charset → UTF-8 → GBK」挑一个能解出正常中文的编码。
     *
     * <p>只认「解出来没有替换字符」这一条判据：中文接口的乱码一定伴随 U+FFFD，
     * 而纯数字/字母的返回体两种编码解出来完全一样，先命中 UTF-8 也无所谓。
     */
    private static String decode(byte[] bytes, String contentType) {
        Charset fromHeader = charsetOf(contentType);
        if (fromHeader != null) {
            String s = new String(bytes, fromHeader);
            if (!s.contains("\uFFFD")) {
                return s;
            }
        }
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) {
            return utf8;
        }
        String gbk = new String(bytes, Charset.forName("GBK"));
        return gbk.contains("\uFFFD") ? utf8 : gbk;
    }

    /** 从 Content-Type 里抠 charset；没写或写了不认识的编码返回 null */
    private static Charset charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        Matcher m = CHARSET_IN_HEADER.matcher(contentType);
        if (!m.find()) {
            return null;
        }
        try {
            return Charset.forName(m.group(1));
        } catch (Exception e) {
            return null;
        }
    }
}
