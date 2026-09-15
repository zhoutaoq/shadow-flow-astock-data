package com.stock.kline.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 大盘指数行情：一次请求取回首页顶部的几个主要指数。
 *
 * <p>两个来源都是免费公开接口，<b>不经过按次计费的供应商接口</b>，首页刷新不产生任何调用费用：
 * <ul>
 *   <li><b>国内</b>走新浪（<code>hq.sinajs.cn</code>）。实测指数返回体 33 个字段、与个股同构
 *       （不是网上说的 6 字段老格式）：
 *       <pre>var hq_str_sh000001="上证指数,3939.0948,3951.5068,3934.4036,...,2026-09-10,15:40:32,00,";</pre>
 *       名称 / 今开 / 昨收 / <b>现价</b> / 最高 / 最低 / 买一 / 卖一 / 成交量 / 成交额 ... 日期 / 时间。
 *       涨跌幅接口不给，按 (现价 - 昨收) / 昨收 自己算。</li>
 *   <li><b>海外</b>走东方财富（<code>push2.eastmoney.com</code>）。这里特意<b>不用新浪的 int_ 全球指数</b>：
 *       实测 <code>int_nikkei / int_sp500 / int_nasdaq</code> 返回的是严重过期的旧值
 *       （标普给 6643.70，同期权威报价是 7619.98；日经给 44946.64，实际 63492.99），
 *       摆在首页会让人看错行情。东财的海外指数与权威报价一致。</li>
 * </ul>
 *
 * <p>两个来源各自缓存、各自降级：一边取不到只影响自己那半边，不会连带把好的也丢掉。
 * 结果为空时前端把指数区整块隐藏，不报错。
 */
@Service
public class MarketIndexService {

    private static final Logger log = LoggerFactory.getLogger(MarketIndexService.class);

    /**
     * 缓存时长：指数刷新没必要比这个更快，也能挡住连续 F5。
     * 两个来源共用这个时长，但各自算各自的过期时间。
     */
    private static final long CACHE_MS = 20_000L;
    private static final int TIMEOUT_MS = 3000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    // ---------------------------------------------------------------- 国内（新浪）

    private static final String SINA_URL = "https://hq.sinajs.cn/list=";
    private static final String SINA_REFERER = "https://finance.sina.com.cn";
    private static final Charset SINA_CHARSET = Charset.forName("GBK");

    /** 上证指数 / 深证成指 / 创业板指 / 科创50 / 沪深300 */
    private static final String[] DOMESTIC_CODES = {
            "sh000001", "sz399001", "sz399006", "sh000688", "sh000300"
    };

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

    // ---------------------------------------------------------------- 海外（东方财富）

    /** fltt=2 让东财直接返回真实小数（不按小数位做 ×100 缩放），省掉自己还原那一步 */
    private static final String EM_URL = "https://push2.eastmoney.com/api/qt/ulist.np/get"
            + "?fltt=2&fields=f2,f3,f4,f12,f14&secids=";
    private static final String EM_REFERER = "https://quote.eastmoney.com/";

    /**
     * 海外指数：{东财 secid, 页面展示名}，数组顺序就是页面上的展示顺序。
     *
     * <p>展示名按页面口径固定，不直接用东财的「日经225 / 韩国KOSPI / 纳斯达克」，
     * 免得和国内那几条的命名风格打架；点位本身仍以接口值为准。
     */
    private static final String[][] OVERSEAS = {
            {"100.N225", "日经指数"},
            {"100.KS11", "韩国综合指数"},
            {"100.SPX", "标普500"},
            {"100.NDX", "纳斯达克"},
    };

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile List<MarketIndex> domesticCache;
    private volatile long domesticAt;
    private volatile List<MarketIndex> overseasCache;
    private volatile long overseasAt;

    /** 指数快照：国内在前、海外在后；某个来源取不到时只影响它自己那一段 */
    public List<MarketIndex> indices() {
        List<MarketIndex> out = new ArrayList<>(DOMESTIC_CODES.length + OVERSEAS.length);
        out.addAll(domestic());
        out.addAll(overseas());
        return out;
    }

    private List<MarketIndex> domestic() {
        long now = System.currentTimeMillis();
        List<MarketIndex> hit = domesticCache;
        if (hit != null && now - domesticAt < CACHE_MS) {
            return hit;
        }
        List<MarketIndex> fresh = fetchDomestic();
        if (!fresh.isEmpty()) {
            domesticCache = fresh;
            domesticAt = now;
            return fresh;
        }
        // 这次没取到：有旧值就先给旧的，没有就空着让前端隐藏
        return hit == null ? Collections.<MarketIndex>emptyList() : hit;
    }

    private List<MarketIndex> overseas() {
        long now = System.currentTimeMillis();
        List<MarketIndex> hit = overseasCache;
        if (hit != null && now - overseasAt < CACHE_MS) {
            return hit;
        }
        List<MarketIndex> fresh = fetchOverseas();
        if (!fresh.isEmpty()) {
            overseasCache = fresh;
            overseasAt = now;
            return fresh;
        }
        return hit == null ? Collections.<MarketIndex>emptyList() : hit;
    }

    // ---------------------------------------------------------------- 取数

    private List<MarketIndex> fetchDomestic() {
        String url = SINA_URL + String.join(",", DOMESTIC_CODES);
        long begin = System.currentTimeMillis();
        String body;
        try {
            body = doGet(url, SINA_REFERER, SINA_CHARSET);
        } catch (IOException e) {
            log.warn("[market] 国内指数行情调用失败 url={} 耗时 {} ms err={}",
                    url, System.currentTimeMillis() - begin, e.getMessage());
            return Collections.emptyList();
        }
        List<MarketIndex> out = new ArrayList<>(DOMESTIC_CODES.length);
        for (String line : body.split(";")) {
            MarketIndex mi = parseSinaLine(line);
            if (mi != null) {
                out.add(mi);
            }
        }
        log.info("[market] 国内指数取回 {} 条，耗时 {} ms", out.size(), System.currentTimeMillis() - begin);
        return out;
    }

    private List<MarketIndex> fetchOverseas() {
        StringBuilder secids = new StringBuilder();
        for (String[] item : OVERSEAS) {
            if (secids.length() > 0) {
                secids.append(',');
            }
            secids.append(item[0]);
        }
        String url = EM_URL + secids;
        long begin = System.currentTimeMillis();
        String body;
        try {
            body = doGet(url, EM_REFERER, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[market] 海外指数行情调用失败 url={} 耗时 {} ms err={}",
                    url, System.currentTimeMillis() - begin, e.getMessage());
            return Collections.emptyList();
        }
        List<MarketIndex> out = new ArrayList<>(OVERSEAS.length);
        try {
            JsonNode diff = mapper.readTree(body).path("data").path("diff");
            for (JsonNode node : diff) {
                MarketIndex mi = parseEmNode(node);
                if (mi != null) {
                    out.add(mi);
                }
            }
        } catch (IOException e) {
            log.warn("[market] 海外指数返回体解析失败 err={} body={}", e.getMessage(), body);
            return Collections.emptyList();
        }
        log.info("[market] 海外指数取回 {} 条，耗时 {} ms", out.size(), System.currentTimeMillis() - begin);
        return out;
    }

    private MarketIndex parseSinaLine(String line) {
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

    /**
     * 东财单条：f12 代码 / f2 现价 / f3 涨跌幅 / f4 涨跌额。
     * 海外指数没有成交额（f6 恒为 0），成交额位留空，前端据此不渲染那一行。
     */
    private MarketIndex parseEmNode(JsonNode node) {
        String code = node.path("f12").asText("");
        BigDecimal point = emNumber(node.get("f2"));
        if (code.isEmpty() || point == null || point.signum() <= 0) {
            return null;
        }
        for (String[] item : OVERSEAS) {
            if (item[0].endsWith("." + code)) {
                return new MarketIndex(item[0], item[1], point,
                        emNumber(node.get("f4")), emNumber(node.get("f3")), null);
            }
        }
        return null;
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

    private String doGet(String urlStr, String referer, Charset charset) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        // 两家都有防盗链，不带 Referer 会被拒
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("User-Agent", USER_AGENT);
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
            return new String(out.toByteArray(), charset);
        }
    }

    /** 东财的数值位在无数据时是字符串 "-"，只有真数字才要 */
    private static BigDecimal emNumber(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
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
