package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;

/**
 * 新浪财经实时行情客户端：GET <code>{baseUrl}/list=sh600000</code>。
 *
 * <p>返回体是 GBK 编码的一行 JS 变量，形如：
 * <pre>var hq_str_sh600000="浦发银行,600000,今开,昨收,现价,最高,最低,买一,卖一,成交量,成交额,...,日期,时间";</pre>
 * 接口有防盗链，请求头必须带 <code>Referer: https://finance.sina.com.cn</code>。
 */
public class SinaQuoteClient {

    private static final Logger log = LoggerFactory.getLogger(SinaQuoteClient.class);

    private static final Charset GBK = Charset.forName("GBK");

    /** 字段下标 */
    private static final int F_NAME = 0;
    private static final int F_PRE_CLOSE = 2;
    private static final int F_PRICE = 3;
    private static final int F_HIGH = 4;
    private static final int F_LOW = 5;

    private final WatchProperties props;

    public SinaQuoteClient(WatchProperties props) {
        this.props = props;
    }

    /**
     * 查一只标的的实时行情。
     *
     * @return 拿到的快照；停牌、代码不存在或请求失败返回 null
     */
    public Quote fetch(WatchTarget target) {
        if (target == null) {
            return null;
        }
        String url = props.getSina().getBaseUrl() + "/list=" + target.getSymbol();
        long begin = System.currentTimeMillis();
        String body;
        try {
            body = doGet(url);
        } catch (IOException e) {
            log.warn("[watch] 行情调用失败 {} url={} 耗时 {} ms err={}",
                    target, url, System.currentTimeMillis() - begin, e.getMessage());
            return null;
        }
        long cost = System.currentTimeMillis() - begin;
        Quote q = parse(target, url, body);
        if (q == null) {
            log.warn("[watch] 行情无有效数据（停牌/代码不存在）{} 耗时 {} ms", target, cost);
            return null;
        }
        BigDecimal pct = q.changePercent();
        log.info("[watch] 行情调用成功 {} 现价 {} 涨跌幅 {} 行情时间 {} 耗时 {} ms",
                target, q.getPrice(),
                pct == null ? "—" : pct.toPlainString() + "%",
                q.getTimeText(), cost);
        return q;
    }

    private Quote parse(WatchTarget target, String url, String body) {
        if (body == null || body.trim().isEmpty()) {
            return null;
        }
        String line = body.trim();
        int begin = line.indexOf('"');
        int end = line.lastIndexOf('"');
        if (begin < 0 || end <= begin) {
            log.warn("[watch] 行情返回格式异常 {} body={}", target, abbr(line));
            return null;
        }
        String[] f = line.substring(begin + 1, end).split(",");
        if (f.length == 1 && f[0].trim().isEmpty()) {
            // 代码不存在时新浪返回空串
            log.warn("[watch] 行情接口无此代码 {} symbol={}", target, target.getSymbol());
            return null;
        }
        if (f.length < 32) {
            log.warn("[watch] 行情字段不足（{} 个）{} body={}", f.length, target, abbr(line));
            return null;
        }
        BigDecimal price = decimal(f[F_PRICE]);
        BigDecimal preClose = decimal(f[F_PRE_CLOSE]);
        if (price == null || price.signum() <= 0) {
            // 停牌 / 未开盘，现价为 0，不参与比对
            return null;
        }
        String name = f[F_NAME] == null || f[F_NAME].trim().isEmpty() ? target.getName() : f[F_NAME].trim();
        String timeText = (f[30] + " " + f[31]).trim();
        return new Quote(target.getCode(), name, price, preClose,
                decimal(f[F_HIGH]), decimal(f[F_LOW]),
                System.currentTimeMillis(), timeText);
    }

    private String doGet(String urlStr) throws IOException {
        WatchProperties.Sina sina = props.getSina();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(sina.getConnectTimeoutMs());
        conn.setReadTimeout(sina.getReadTimeoutMs());
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Referer", "https://finance.sina.com.cn");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36");

        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new IOException("HTTP " + status);
        }
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), GBK);
        }
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String abbr(String s) {
        String one = s.replaceAll("\\s+", " ");
        return one.length() > 120 ? one.substring(0, 120) + "..." : one;
    }
}
