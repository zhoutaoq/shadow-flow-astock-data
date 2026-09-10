package com.stock.kline.market;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 大盘指数行情：一次请求取回沪深几个主要指数，供首页展示。
 *
 * <p>走新浪的公开行情接口（<code>hq.sinajs.cn</code>），<b>不经过按次计费的供应商接口</b>，
 * 所以首页刷新不产生任何调用费用。指数与个股的返回体字段数不同：
 * <pre>var hq_str_sh000001="上证指数,3939.0948,3951.5068,3934.4036,...,2026-09-10,15:40:32,00,";</pre>
 * 实测是 33 个字段、与个股同构（不是网上说的 6 字段老格式）：
 * 名称 / 今开 / 昨收 / <b>现价</b> / 最高 / 最低 / 买一 / 卖一 / 成交量 / 成交额 ... 日期 / 时间。
 * 涨跌幅接口不给，按 (现价 - 昨收) / 昨收 自己算。
 *
 * <p>结果在内存里缓存一小段时间，避免每次刷新都去打新浪。
 * 取不到时返回空列表（或上一轮的旧值），前端据此隐藏指数区，不报错。
 */
@Service
public class MarketIndexService {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexService.class);

    private static final String BASE_URL = "https://hq.sinajs.cn/list=";
    private static final Charset GBK = Charset.forName("GBK");
    /** 缓存时长：指数刷新没必要比这个更快，也能挡住连续 F5 */
    private static final long CACHE_MS = 20_000L;
    private static final int TIMEOUT_MS = 3000;

    /**
     * 字段下标：实测指数与个股返回<b>完全相同</b>的 33 字段布局
     * （网上流传的"指数只有 6 个字段"是老接口），可以直接复用盯盘那套下标。
     * 名称 / 今开 / 昨收 / 现价 / 最高 / 最低 / 买一 / 卖一 / 成交量 / 成交额
     */
    private static final int F_NAME = 0;
    private static final int F_PRE_CLOSE = 2;
    private static final int F_PRICE = 3;
    private static final int F_AMOUNT = 9;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /** 上证指数 / 深证成指 / 创业板指 / 科创50 / 沪深300 */
    private static final String[] CODES = {
            "sh000001", "sz399001", "sz399006", "sh000688", "sh000300"
    };

    private volatile List<MarketIndex> cache;
    private volatile long cacheAt;

    /** 指数快照；取不到返回空列表 */
    public List<MarketIndex> indices() {
        long now = System.currentTimeMillis();
        List<MarketIndex> hit = cache;
        if (hit != null && now - cacheAt < CACHE_MS) {
            return hit;
        }
        List<MarketIndex> fresh = fetch();
        if (fresh != null && !fresh.isEmpty()) {
            cache = fresh;
            cacheAt = now;
            return fresh;
        }
        // 这次没取到：有旧值就先给旧的，没有就空着让前端隐藏
        return hit == null ? Collections.<MarketIndex>emptyList() : hit;
    }

    private List<MarketIndex> fetch() {
        String url = BASE_URL + String.join(",", CODES);
        String body;
        long begin = System.currentTimeMillis();
        try {
            body = doGet(url);
        } catch (IOException e) {
            log.warn("[market] 指数行情调用失败 url={} 耗时 {} ms err={}",
                    url, System.currentTimeMillis() - begin, e.getMessage());
            return null;
        }
        List<MarketIndex> out = new ArrayList<>(CODES.length);
        for (String line : body.split(";")) {
            MarketIndex mi = parseLine(line);
            if (mi != null) {
                out.add(mi);
            }
        }
        log.info("[market] 指数行情取回 {} 条，耗时 {} ms", out.size(), System.currentTimeMillis() - begin);
        return out;
    }

    private MarketIndex parseLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        int begin = line.indexOf('"');
        int end = line.lastIndexOf('"');
        if (begin < 0 || end <= begin) {
            return null;
        }
        String symbol = symbolOf(line);
        String[] f = line.substring(begin + 1, end).split(",");
        if (f.length < 10) {
            return null;
        }
        BigDecimal point = decimal(f[F_PRICE]);
        BigDecimal preClose = decimal(f[F_PRE_CLOSE]);
        if (point == null || point.signum() <= 0) {
            // 非交易时段或数据异常时点数为 0，不展示
            return null;
        }
        String name = f[F_NAME] == null || f[F_NAME].trim().isEmpty() ? symbol : f[F_NAME].trim();
        // 涨跌幅自己算：接口不单独给这个字段，口径与页面其它地方保持一致
        BigDecimal change = null;
        BigDecimal pct = null;
        if (preClose != null && preClose.signum() > 0) {
            change = point.subtract(preClose);
            pct = change.multiply(HUNDRED).divide(preClose, 2, RoundingMode.HALF_UP);
        }
        return new MarketIndex(symbol, name, point, change, pct, decimal(f[F_AMOUNT]));
    }

    /** 从 <code>var hq_str_sh000001="..."</code> 里取出 sh000001 */
    private static String symbolOf(String line) {
        int at = line.indexOf("hq_str_");
        if (at < 0) {
            return "";
        }
        String rest = line.substring(at + "hq_str_".length());
        int eq = rest.indexOf('=');
        return eq < 0 ? rest.trim() : rest.substring(0, eq).trim();
    }

    private String doGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        // 有防盗链，不带 Referer 会被拒
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
}
