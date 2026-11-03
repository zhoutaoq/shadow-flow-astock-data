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
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 实时行情查看：行业板块成交额 / 涨跌幅 + 全市场涨跌与涨跌停家数。
 *
 * <p><b>为什么是这两家，而不是东财</b>：东财的 {@code push2.eastmoney.com} 是本项目最爱用的行情源，
 * 但线上这台机器连不上它 —— 实测 DNS 解到 IPv6 {@code 2406:d440:...}、IPv4 {@code 61.129.129.196}，
 * 两条路都是 TCP 通、TLS 之后空回复；强制 IPv4、换 {@code push2delay}、借同门
 * {@code push2ex} / {@code datacenter-web} 的 IP 都试过，全 000。同门的
 * {@code push2ex}（涨停池）和 {@code datacenter-web} 却是通的，所以不是整站封禁，是这一条链路的问题。
 * 结论：板块与家数改用线上验证过的两家 ——
 * <pre>
 *   行业板块  新浪 vip.stock.finance.sina.com.cn/q/view/newSinaHy.php
 *   涨跌家数  同花顺 q.10jqka.com.cn/api.php?t=indexflash
 * </pre>
 *
 * <p><b>资金流没有做</b>：线上拿不到可靠的板块资金流 —— 东财（f62 主力净流入）连不上，
 * 新浪的 {@code MoneyFlow.ssl_bkzj_bk} 已废弃（Input error），同花顺资金流页是反爬壳。
 * 与其摆一列假数字，不如这一版先不给。
 */
@Service
public class RealtimeBoardService {

    private static final Logger log = LoggerFactory.getLogger(RealtimeBoardService.class);

    /** 板块数据 10 秒够新：行业涨跌幅不会 3 秒变一次，太勤只会被上游限流 */
    private static final long CACHE_MS = 10_000L;
    private static final int TIMEOUT_MS = 5_000;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    private static final String SINA_BOARD_URL =
            "https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php";
    private static final String SINA_REFERER = "https://finance.sina.com.cn";

    private static final String THS_STATS_URL = "https://q.10jqka.com.cn/api.php?t=indexflash";
    private static final String THS_REFERER = "https://q.10jqka.com.cn/";

    /**
     * 新浪返回体是一行赋值语句，值里按逗号排了 13 段：
     * <pre>
     * 0 代码 1 名称 2 成分股数 3 均价 4 涨跌额 5 涨跌幅% 6 成交量(股) 7 成交额(元)
     * 8 领涨股代码 9 领涨股涨跌幅 10 领涨股现价 11 领涨股涨跌额 12 领涨股名称
     * </pre>
     */
    private static final Pattern SINA_PAIR = Pattern.compile("\"([a-z_0-9]+)\":\"([^\"]+)\"");

    private final ObjectMapper mapper = new ObjectMapper();

    private volatile Map<String, Object> cached;
    private volatile long cachedAt;

    /** 板块 + 市场统计，一次给全；取不到的段自己留空，不拖累另一段 */
    public Map<String, Object> snapshot() {
        long now = System.currentTimeMillis();
        Map<String, Object> hit = cached;
        if (hit != null && now - cachedAt < CACHE_MS) {
            return hit;
        }
        List<Map<String, Object>> boards = boards();
        Map<String, Object> stats = stats();
        if (boards.isEmpty() && stats.isEmpty()) {
            return hit == null ? new LinkedHashMap<String, Object>() : hit;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", LocalTime.now().withNano(0).toString());
        out.put("stats", stats);
        out.put("boards", boards);
        cached = out;
        cachedAt = now;
        return out;
    }

    // ------------------------------------------------------------ 行业板块（新浪）

    private List<Map<String, Object>> boards() {
        List<Map<String, Object>> out = new ArrayList<>();
        String body;
        try {
            body = doGet(SINA_BOARD_URL, SINA_REFERER, Charset.forName("GBK"));
        } catch (Exception e) {
            log.warn("[realtime] 板块取数失败: {}", e.getMessage());
            return out;
        }
        Matcher m = SINA_PAIR.matcher(body);
        while (m.find()) {
            String[] f = m.group(2).split(",");
            if (f.length < 13) {
                continue;
            }
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("code", m.group(1));
            b.put("name", f[1]);
            b.put("stockCount", (int) parse(f[2]));
            b.put("changePercent", scale(parse(f[5]), 2));
            b.put("amount", scale(parse(f[7]), 2));
            b.put("leadCode", f[8]);
            b.put("leadName", f[12]);
            b.put("leadChangePercent", scale(parse(f[9]), 2));
            out.add(b);
        }
        // 涨得多的排前面，和行情软件的习惯一致
        out.sort((a, b) -> Double.compare(num(b.get("changePercent")), num(a.get("changePercent"))));
        return out;
    }

    // ------------------------------------------------------------ 涨跌 / 涨跌停家数（同花顺）

    private Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            String body = doGet(THS_STATS_URL, THS_REFERER, StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(body);
            JsonNode zd = root.path("zdt_data").path("last_zdt");
            JsonNode fb = root.path("zdfb_data");
            out.put("limitUp", zd.path("ztzs").asInt(0));
            out.put("limitDown", zd.path("dtzs").asInt(0));
            out.put("up", fb.path("znum").asInt(0));
            out.put("down", fb.path("dnum").asInt(0));
            out.put("avgChangePercent", scale(root.path("jrbx_data").path("last_zdf").asDouble(0), 2));
        } catch (Exception e) {
            log.warn("[realtime] 涨跌家数取数失败: {}", e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------ 工具

    private static double num(Object v) {
        return v instanceof BigDecimal ? ((BigDecimal) v).doubleValue() : 0;
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

    private static BigDecimal scale(double v, int digits) {
        return BigDecimal.valueOf(v).setScale(digits, RoundingMode.HALF_UP);
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
