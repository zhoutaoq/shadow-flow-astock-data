package io.github.zhoutaoq.shadowflow.biz.market;

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
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 上证 / 深证 / 创业板三个指数的成交额（首页「市场成交」卡）。
 *
 * <p>口径都是「该市场全部股票」：
 * <pre>
 *   上证   = 上证指数（沪市全部，含科创板）
 *   深证   = 深证综指（深市全部，含创业板）
 *   创业板 = 创业板综
 * </pre>
 * 深证没用成指：成指的分时只给 500 只成分股的成交额，实时接口给的却是深市全量，
 * 两者差一倍（2026-10-28 实测 1476 亿 vs 3070 亿），拿它对不上。
 *
 * <p>比较基准取<b>昨日同期</b>而不是昨日全天：盘中拿今日累计去比昨日全天，上午永远是
 * 一个大负数，看不出放量缩量。两天的分时都走新浪 1 分钟 K 线，按同样的根数累加再相减，
 * 这样集合竞价和最后一根半根的零头在相减时抵掉。
 *
 * <p>两个来源都只读、都免费，且各自降级：实时取不到就返回空，分时取不到就把增减额留空。
 */
@Service
public class MarketTurnoverService {

    private static final Logger log = LoggerFactory.getLogger(MarketTurnoverService.class);

    /** 与首页行情带同频：页面 3 秒刷，缓存 2 秒，保证每次轮询都拿到新数又压住并发 */
    private static final long CACHE_MS = 2_000L;
    private static final int TIMEOUT_MS = 3_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    /** 代码 + 显示名，顺序即接口返回的顺序 */
    private static final String[][] INDEX = {
            {"sh000001", "上证"},
            {"sz399106", "深证"},
            {"sz399102", "创业板"},
    };
    /** 新浪指数序列里成交额的下标（名称 0 / 今开 1 / 昨收 2 / 现价 3 / 最高 4 / 最低 5 / 量 8 / 额 9） */
    private static final int F_AMOUNT = 9;

    /**
     * 分时：走新浪的 1 分钟 K 线，与实时那条同为新浪，口径一致。
     *
     * <p>为什么是 1 分钟不是 5 分钟：最后一根是没走完的半根，5 分钟线的半根最多差 5 分钟的
     * 成交，盘中能占两三个百分点，足以把放量显示成缩量。1 分钟的半根也就差几十秒。
     *
     * <p>返回体：<code>{"result":{"data":[{"day":"2026-10-28 10:04:00","amount":"1494761472.0000"},...]}}</code>，
     * 升序，一根一条，amount 单位元。
     */
    private static final String SINA_KLINE =
            "https://quotes.sina.cn/cn/api/openapi.php/CN_MarketDataService.getKLineData?symbol=";
    private static final String SINA_REFERER = "https://finance.sina.com.cn";
    /** 一天 239 根，600 根能稳稳覆盖「今日已走 + 昨日全天」 */
    private static final int KLINE_BARS = 600;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 2 秒缓存（整个结果） */
    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    /** 分时的缓存：每分钟才变一次，60 秒够用 */
    private volatile DayCurve curve;
    private volatile long curveAt;

    /**
     * 当前成交额 + 较昨日同期的增减。
     *
     * @return 取不到实时数据时返回空 map（前端把整块隐藏，不报错）
     */
    public Map<String, Object> turnover() {
        long now = System.currentTimeMillis();
        Map<String, Object> hit = cached;
        if (hit != null && now - cachedAt < CACHE_MS) {
            return hit;
        }
        Map<String, Object> out = build();
        if (!out.isEmpty()) {
            cached = out;
            cachedAt = now;
        }
        return out;
    }

    private Map<String, Object> build() {
        double[] live = fetchLive();
        if (live == null) {
            return new LinkedHashMap<>();
        }
        DayCurve c = curve();

        List<Map<String, Object>> items = new ArrayList<>(INDEX.length);
        for (int i = 0; i < INDEX.length; i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", INDEX[i][1]);
            m.put("amount", round2(live[i]));
            double prev = c == null ? 0 : c.prev(i);
            if (c == null || prev <= 0) {
                m.put("delta", null);
                m.put("percent", null);
            } else {
                double delta = c.today(i) - prev;
                m.put("delta", round2(delta));
                m.put("percent", BigDecimal.valueOf(delta * 100.0 / prev)
                        .setScale(2, RoundingMode.HALF_UP));
            }
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", LocalTime.now().withNano(0).toString());
        out.put("prevDate", c == null ? "" : c.prevDate);
        out.put("basis", "昨日同期");
        out.put("items", items);
        return out;
    }

    private static Double round2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    // ------------------------------------------------------------ 实时

    /** 三个指数的最新成交额（元）；取不到返回 null */
    private double[] fetchLive() {
        StringBuilder url = new StringBuilder("https://hq.sinajs.cn/list=");
        for (int i = 0; i < INDEX.length; i++) {
            if (i > 0) {
                url.append(',');
            }
            url.append(INDEX[i][0]);
        }
        try {
            String body = doGet(url.toString(), SINA_REFERER, Charset.forName("GBK"));
            double[] out = new double[INDEX.length];
            boolean[] seen = new boolean[INDEX.length];
            for (String line : body.split("\\r?\\n")) {
                int key = line.indexOf("hq_str_");
                int q1 = line.indexOf('"');
                int q2 = line.lastIndexOf('"');
                if (key < 0 || q1 < 0 || q2 <= q1) {
                    continue;
                }
                String symbol = line.substring(key + 7, line.indexOf('=', key)).trim();
                String[] f = line.substring(q1 + 1, q2).split(",");
                if (f.length <= F_AMOUNT) {
                    continue;
                }
                for (int i = 0; i < INDEX.length; i++) {
                    if (INDEX[i][0].equals(symbol)) {
                        out[i] = parse(f[F_AMOUNT]);
                        seen[i] = true;
                    }
                }
            }
            for (boolean s : seen) {
                if (!s) {
                    return null;
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[market] 成交额实时取数失败: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------ 分时（今日 / 昨日）

    /** 昨日全天 + 今日已走的分时，每分钟一根，单位元 */
    private static final class DayCurve {
        String prevDate = "";
        double[][] prev = new double[0][];
        double[][] today = new double[0][];
        /** 加到的根数；只算走完的根，最后一根半根不计 */
        int bars;

        double prev(int i) {
            return sum(prev[i]);
        }

        double today(int i) {
            return sum(today[i]);
        }

        private double sum(double[] arr) {
            double total = 0;
            for (int k = 0; k < bars && k < arr.length; k++) {
                total += arr[k];
            }
            return total;
        }
    }

    private DayCurve curve() {
        long now = System.currentTimeMillis();
        DayCurve c = curve;
        if (c != null && now - curveAt < 60_000L) {
            return c;
        }
        DayCurve fresh = fetchCurve();
        if (fresh != null) {
            curve = fresh;
            curveAt = now;
            return fresh;
        }
        return c;
    }

    /**
     * 拉三个指数的 1 分钟 K 线，分开今天已走的和昨日全天的逐根成交额。
     *
     * <p>失败时返回 null，调用方把增减额留空 —— 宁可不显示，也不要拿错数去比。
     */
    private DayCurve fetchCurve() {
        String today = LocalDate.now().format(DAY);
        DayCurve out = new DayCurve();
        out.prev = new double[INDEX.length][0];
        out.today = new double[INDEX.length][0];
        int bars = Integer.MAX_VALUE;

        for (int i = 0; i < INDEX.length; i++) {
            try {
                String url = SINA_KLINE + INDEX[i][0] + "&scale=1&ma=no&datalen=" + KLINE_BARS;
                String body = doGet(url, SINA_REFERER, java.nio.charset.StandardCharsets.UTF_8);
                JsonNode all = mapper.readTree(body).path("result").path("data");
                if (!all.isArray() || all.size() == 0) {
                    return null;
                }
                // 数据升序：遇到更晚的日期就把昨日的缓冲清掉，最后留下的那段就是昨日
                List<Double> curDay = new ArrayList<>();
                List<Double> prevDay = new ArrayList<>();
                String prevDate = "";
                for (JsonNode bar : all) {
                    String day = bar.path("day").asText("");
                    if (day.length() < 10) {
                        continue;
                    }
                    String d = day.substring(0, 10);
                    double amt = parse(bar.path("amount").asText(""));
                    if (d.equals(today)) {
                        curDay.add(amt);
                    } else if (d.compareTo(today) < 0) {
                        if (!d.equals(prevDate)) {
                            prevDay.clear();
                            prevDate = d;
                        }
                        prevDay.add(amt);
                    }
                }
                out.today[i] = toArray(curDay);
                out.prev[i] = toArray(prevDay);
                out.prevDate = prevDate;
                // 两天取的根数必须一样，否则「同期」不成立
                bars = Math.min(bars, Math.min(out.today[i].length, out.prev[i].length));
            } catch (Exception e) {
                log.warn("[market] 分时取数失败 {}: {}", INDEX[i][0], e.getMessage());
                return null;
            }
        }
        if (bars == Integer.MAX_VALUE) {
            return null;
        }
        // 今日最后一根还没走完，少算一根后两边都是完整的分钟
        out.bars = Math.max(0, bars - 1);
        return out;
    }

    // ------------------------------------------------------------ 工具

    private static double[] toArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    private static double parse(String s) {
        if (s == null) {
            return 0;
        }
        String v = s.trim();
        if (v.isEmpty() || "-".equals(v)) {
            return 0;
        }
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String doGet(String urlStr, String referer, Charset charset) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Referer", referer);
        conn.setRequestProperty("User-Agent", USER_AGENT);
        int status = conn.getResponseCode();
        if (status >= 400) {
            throw new IOException("HTTP " + status);
        }
        try (InputStream in = conn.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), charset);
        } finally {
            conn.disconnect();
        }
    }
}
