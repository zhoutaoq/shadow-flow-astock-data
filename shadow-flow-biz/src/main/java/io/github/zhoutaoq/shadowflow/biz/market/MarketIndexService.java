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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 大盘指数行情：一次请求取回首页顶部的几个主要指数。
 *
 * <p>两个来源都是公开接口，<b>不经过行情数据服务</b>，首页刷新不产生任何调用费用：
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
     * 缓存时长：首页那条行情带每 3 秒拉一次，缓存必须比它短，否则轮询拿到的永远是同一份旧值。
     * 取 2 秒 —— 比页面的 3 秒间隔略短，既保证每次轮询都真取到新数，又能把
     * 「多开几个标签页 / 多人同时看首页」的并发压成最多每 2 秒一次上游请求。
     * 两个来源共用这个时长，但各自算各自的过期时间。
     */
    private static final long CACHE_MS = 2_000L;
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

    /**
     * 东财行情宿主，按顺序试第一个成功的。
     * fltt=2 让东财直接返回真实小数（不按小数位做 ×100 缩放），省掉自己还原那一步。
     *
     * <p>主干域名 <code>push2.eastmoney.com</code> 在部分机房出口会被拒 —— 实测线上
     * <code>111.231.171.218</code> 上 TCP 能建连，但 TLS 之后直接空回复（curl 报
     * <i>Empty reply from server</i>，Java 报 <i>Unexpected end of file from server</i>），
     * 换 Referer / UA / 80 端口 / 各数字子域（<code>1. 7. 82.</code>）都一样。
     * <code>push2delay.eastmoney.com</code> 在同一台机器上正常，且实测 12 秒内标普 / 纳指 /
     * 费半的点位都在动，是<b>实时数据</b>（名字里的 delay 不代表延迟行情）。
     * 两个宿主都留着、按顺序取第一个成功的：换机房或东财解封都不用改代码。
     */
    private static final String[] EM_HOSTS = {
            "https://push2.eastmoney.com",
            "https://push2delay.eastmoney.com",
    };
    private static final String EM_PATH = "/api/qt/ulist.np/get?fltt=2&fields=f2,f3,f4,f12,f14&secids=";
    private static final String EM_REFERER = "https://quote.eastmoney.com/";

    /**
     * 海外指数：{东财 secid, 页面展示名}，数组顺序就是页面上的展示顺序。
     *
     * <p>展示名按需求口径固定，不直接用东财的「日经225 / 韩国KOSPI / 纳斯达克」，
     * 免得和国内那几条的命名风格打架；点位本身仍以接口值为准。
     *
     * <p><b>费城半导体指数（SOX）不在东财的国际指数板块里</b> —— 板块
     * <code>fs=m:100</code> 全量 63 条里没有它，<code>100.SOX</code> 也查不到数据。
     * 它的 secid 是 <code>251.SOX</code>（搜索接口回的 QuoteID，MktNum=251），
     * 与其它国际指数不同市场，但同一个 ulist 接口能一次取回，所以混在这一组里没有额外代价。
     */
    private static final String[][] OVERSEAS = {
            {"100.SPX", "标普500指数"},
            {"100.NDX", "纳斯达克指数"},
            {"251.SOX", "费城半导体指数"},
            {"100.N225", "日经225"},
            {"100.KS11", "韩国综指"},
    };

    /**
     * 腾讯全球指数：{腾讯代码, 页面展示名}。
     *
     * <p>只放腾讯确实提供的两个：{@code usINX} 是标普 500（不是 usSPX），{@code usIXIC} 是纳指。
     * 费半/日经/韩国综指在腾讯没有对应代码（实测 usSOX / jpN225 / krKS11 都返回空），
     * 那三个仍由东财补，线上拿不到就少几行，但不影响标普纳指这两条。
     */
    private static final String[][] TENCENT_OVERSEAS = {
            {"usINX", "标普500指数"},
            {"usIXIC", "纳斯达克指数"},
    };

    private static final String TENCENT_URL = "https://qt.gtimg.cn/q=";
    private static final String TENCENT_REFERER = "https://finance.qq.com";

    /**
     * 百度股市通全球指数：{代码, 市场, 展示名}。
     *
     * <p>五个全齐，且数值与东财同一时刻一致（实测 SOX 10268.65 / NK225 61077.90 /
     * SPX 7688.62 / IXIC 25736.51 / KOSPI 5383.80）。腾讯缺日经和韩国，东财在线上被整片拒绝，
     * 所以百度做主源、另两家兜底。
     *
     * <p>接口是单只查询，不能批量：五个请求串起来约 0.5 秒，外层有 2 秒缓存挡着，代价可接受。
     */
    private static final String[][] BAIDU_OVERSEAS = {
            {"SPX", "us", "标普500指数"},
            {"IXIC", "us", "纳斯达克指数"},
            {"SOX", "us", "费城半导体指数"},
            {"NK225", "jp", "日经225"},
            {"KOSPI", "kr", "韩国综指"},
    };
    private static final String BAIDU_URL = "https://finance.pae.baidu.com/vapi/v1/getquotation"
            + "?srcid=5353&pointType=string&group=quotation_index_minute&newFormat=1";
    private static final String BAIDU_REFERER = "https://finance.baidu.com/";

    private static final Pattern BAIDU_PRICE = Pattern.compile("\"price\":\"([0-9.]+)\"");
    private static final Pattern BAIDU_RATIO = Pattern.compile("\"ratio\":\"(-?[0-9.]+)\"");
    private static final Pattern BAIDU_PRECLOSE =
            Pattern.compile("\"name\":\"昨收\",\"value\":\"([0-9.]+)\"");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 失败日志节流：首页那条带子每 3 秒来一次，上游不通时若每次都打，
     * 一天能刷出几万行日志把 app.log 撑爆。同一边一分钟最多报一条，够定位问题。
     */
    private static final long WARN_THROTTLE_MS = 60_000L;

    private volatile List<MarketIndex> domesticCache;
    private volatile long domesticAt;
    private volatile List<MarketIndex> overseasCache;
    private volatile long overseasAt;
    private volatile long domesticWarnAt;
    private volatile long overseasWarnAt;

    /** 指数快照：国内标 cn、海外标 global，首页按这个分 TAB；某段取不到只影响它自己 */
    public List<MarketIndex> indices() {
        List<MarketIndex> cn = domestic();
        List<MarketIndex> gl = overseas();
        List<MarketIndex> out = new ArrayList<>(cn.size() + gl.size());
        for (MarketIndex m : cn) {
            m.setMarket("cn");
            out.add(m);
        }
        for (MarketIndex m : gl) {
            m.setMarket("global");
            out.add(m);
        }
        for (MarketIndex m : out) {
            fillDerived(m);
        }
        return out;
    }

    /**
     * 上游没给的那一项，用另外两项推出来。
     *
     * <p>海外指数有四个源（东财 / 腾讯 / 百度 / 新浪），各自缺的字段不一样 ——
     * 实测腾讯给全了三项，但线上走的另一条只给了点位和涨跌额，涨跌幅是空的，
     * 首页那一格就成了空白。既然「涨跌幅 = 涨跌额 ÷ 昨收」「昨收 = 点位 − 涨跌额」，
     * 有点位和涨跌额就一定能算出涨跌幅，不该因为某个源少一列就整格空着。
     */
    private void fillDerived(MarketIndex m) {
        BigDecimal point = m.getPoint();
        if (point == null || point.signum() <= 0) {
            return;
        }
        BigDecimal change = m.getChange();
        BigDecimal pct = m.getChangePercent();
        BigDecimal hundred = BigDecimal.valueOf(100);

        if (change == null && pct != null) {
            BigDecimal factor = BigDecimal.ONE.add(pct.divide(hundred, 6, BigDecimal.ROUND_HALF_UP));
            if (factor.signum() > 0) {
                BigDecimal pre = point.divide(factor, 6, BigDecimal.ROUND_HALF_UP);
                m.setChange(point.subtract(pre).setScale(4, BigDecimal.ROUND_HALF_UP));
            }
            return;
        }
        if (pct == null && change != null) {
            BigDecimal pre = point.subtract(change);
            if (pre.signum() > 0) {
                m.setChangePercent(change.multiply(hundred)
                        .divide(pre, 2, BigDecimal.ROUND_HALF_UP));
            }
        }
    }

    private List<MarketIndex> domestic() {
        long now = System.currentTimeMillis();
        List<MarketIndex> hit = domesticCache;
        if (now - domesticAt < CACHE_MS) {
            return hit == null ? Collections.<MarketIndex>emptyList() : hit;
        }
        List<MarketIndex> fresh = fetchDomestic();
        // 取不到也记时间戳：首页每 3 秒轮询一次，不盖这个戳就会变成
        // 「只要上游不通，每个访客每 3 秒都真打一次上游」。旧值留着继续用。
        domesticAt = now;
        if (!fresh.isEmpty()) {
            domesticCache = fresh;
            return fresh;
        }
        return hit == null ? Collections.<MarketIndex>emptyList() : hit;
    }

    private List<MarketIndex> overseas() {
        long now = System.currentTimeMillis();
        List<MarketIndex> hit = overseasCache;
        if (now - overseasAt < CACHE_MS) {
            return hit == null ? Collections.<MarketIndex>emptyList() : hit;
        }
        List<MarketIndex> fresh = fetchOverseas();
        overseasAt = now;
        if (!fresh.isEmpty()) {
            overseasCache = fresh;
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
            warnThrottled(true, url, System.currentTimeMillis() - begin, e);
            return Collections.emptyList();
        }
        List<MarketIndex> out = new ArrayList<>(DOMESTIC_CODES.length);
        for (String line : body.split(";")) {
            MarketIndex mi = parseSinaLine(line);
            if (mi != null) {
                out.add(mi);
            }
        }
        log.debug("[market] 国内指数取回 {} 条，耗时 {} ms", out.size(), System.currentTimeMillis() - begin);
        return out;
    }

    private List<MarketIndex> fetchOverseas() {
        // 三级合并，按展示名去重（三家返回的 code 各不一样，名字是我们自己定的，稳定）：
        // 百度五个全有 → 腾讯补标普/纳指 → 东财补剩下的（线上基本拿不到，留作本机与将来兜底）
        Map<String, MarketIndex> merged = new LinkedHashMap<>();
        for (MarketIndex mi : fetchOverseasFromBaidu()) {
            merged.put(mi.getName(), mi);
        }
        for (MarketIndex mi : fetchOverseasFromTencent()) {
            merged.putIfAbsent(mi.getName(), mi);
        }
        for (MarketIndex mi : fetchOverseasFromEastmoney()) {
            merged.putIfAbsent(mi.getName(), mi);
        }
        return new ArrayList<>(merged.values());
    }

    /** 百度股市通：单只查询，五个指数逐个取；任一失败只跳过它自己 */
    private List<MarketIndex> fetchOverseasFromBaidu() {
        List<MarketIndex> out = new ArrayList<>();
        for (String[] item : BAIDU_OVERSEAS) {
            String url = BAIDU_URL + "&query=" + item[0] + "&code=" + item[0] + "&market_type=" + item[1];
            try {
                String body = doGet(url, BAIDU_REFERER, StandardCharsets.UTF_8);
                MarketIndex mi = parseBaidu(body, item[0], item[2]);
                if (mi != null) {
                    out.add(mi);
                }
            } catch (IOException e) {
                log.debug("[market] 百度 {} 取数失败：{}", item[0], e.getMessage());
            }
        }
        return out;
    }

    /**
     * 解析百度单只指数：{@code "price":"7688.62"}、{@code "ratio":"1.58"}、
     * 昨收在 kv 列表里（{@code "name":"昨收","value":"..."}），涨跌额用它反算。
     */
    private MarketIndex parseBaidu(String body, String code, String displayName) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        BigDecimal price = match(BAIDU_PRICE, body);
        if (price == null || price.signum() <= 0) {
            return null;
        }
        BigDecimal pct = match(BAIDU_RATIO, body);
        BigDecimal preClose = match(BAIDU_PRECLOSE, body);
        BigDecimal change = preClose != null && preClose.signum() > 0
                ? price.subtract(preClose) : null;
        return new MarketIndex(code, displayName, price, change, pct, null);
    }

    private static BigDecimal match(Pattern p, String body) {
        Matcher m = p.matcher(body);
        if (!m.find()) {
            return null;
        }
        try {
            return new BigDecimal(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 东财那一路：多宿主轮询，全失败按分钟节流报一条 WARN */
    private List<MarketIndex> fetchOverseasFromEastmoney() {
        StringBuilder secids = new StringBuilder();
        for (String[] item : OVERSEAS) {
            if (secids.length() > 0) {
                secids.append(',');
            }
            secids.append(item[0]);
        }
        long begin = System.currentTimeMillis();
        String lastUrl = null;
        IOException lastError = null;
        for (String host : EM_HOSTS) {
            String url = host + EM_PATH + secids;
            lastUrl = url;
            String body;
            try {
                body = doGet(url, EM_REFERER, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // 逐宿主记 DEBUG：主干不可用时每个宿主都会中招，落 DEBUG 免得跟着 3 秒轮询刷日志；
                // 全部宿主失败才按分钟节流报一条 WARN（见下）
                log.debug("[market] 海外指数宿主 {} 取数失败 err={}", host, e.getMessage());
                lastError = e;
                continue;
            }
            List<MarketIndex> parsed = parseOverseas(body);
            if (!parsed.isEmpty()) {
                log.debug("[market] 海外指数取回 {} 条（宿主 {}），耗时 {} ms",
                        parsed.size(), host, System.currentTimeMillis() - begin);
                return parsed;
            }
            lastError = new IOException("宿主 " + host + " 返回体无有效数据");
        }
        if (lastError != null) {
            warnThrottled(false, lastUrl, System.currentTimeMillis() - begin, lastError);
        }
        return Collections.emptyList();
    }

    /**
     * 腾讯全球指数：一行形如 {@code v_usIXIC="200~纳斯达克~.IXIC~25736.51~25372.01~25652.78~..."}。
     *
     * <p>字段用 {@code ~} 分隔：1 名称、3 现价、4 昨收、31 涨跌额、32 涨跌幅。
     * 与东财同一时刻数值一致，作为线上主源；取不到再回落到东财。
     */
    private List<MarketIndex> fetchOverseasFromTencent() {
        List<MarketIndex> out = new ArrayList<>();
        StringBuilder q = new StringBuilder();
        for (String[] item : TENCENT_OVERSEAS) {
            if (q.length() > 0) {
                q.append(',');
            }
            q.append(item[0]);
        }
        String body;
        try {
            body = doGet(TENCENT_URL + q, TENCENT_REFERER, Charset.forName("GBK"));
        } catch (IOException e) {
            log.debug("[market] 腾讯海外指数取数失败：{}", e.getMessage());
            return out;
        }
        Map<String, MarketIndex> parsed = new HashMap<>();
        for (String line : body.split(";")) {
            MarketIndex mi = parseTencentLine(line);
            if (mi != null) {
                parsed.put(mi.getCode(), mi);
            }
        }
        for (String[] item : TENCENT_OVERSEAS) {
            MarketIndex mi = parsed.get(item[0]);
            if (mi != null) {
                mi.setName(item[1]);
                out.add(mi);
            }
        }
        return out;
    }

    /** 一行腾讯指数的解析；代码取 {@code v_xxx=} 里的 xxx，保证与 TENCENT_OVERSEAS 对得上 */
    private MarketIndex parseTencentLine(String line) {
        int varAt = line.indexOf("v_");
        int eq = line.indexOf('=');
        int begin = line.indexOf('"');
        int end = line.lastIndexOf('"');
        if (varAt < 0 || eq <= varAt || begin < 0 || end <= begin) {
            return null;
        }
        String key = line.substring(varAt + 2, eq).trim();
        String[] f = line.substring(begin + 1, end).split("~");
        if (f.length < 33) {
            return null;
        }
        BigDecimal point = decimal(f[3]);
        if (point == null || point.signum() <= 0) {
            return null;
        }
        return new MarketIndex(key, f[1], point, decimal(f[31]), decimal(f[32]), null);
    }

    /**
     * 解析东财 ulist 返回体，按 OVERSEAS 的固定顺序输出。
     * 接口回的次序不保证稳定，直接照它遍历会让首页那几条指数的排列顺序跟着上游变，
     * 所以先按 f12 代码归位再按 OVERSEAS 取。
     */
    private List<MarketIndex> parseOverseas(String body) {
        List<MarketIndex> out = new ArrayList<>(OVERSEAS.length);
        try {
            JsonNode diff = mapper.readTree(body).path("data").path("diff");
            Map<String, JsonNode> byCode = new HashMap<>();
            for (JsonNode node : diff) {
                String code = node.path("f12").asText("");
                if (!code.isEmpty()) {
                    byCode.put(code, node);
                }
            }
            for (String[] item : OVERSEAS) {
                int dot = item[0].indexOf('.');
                String code = dot < 0 ? item[0] : item[0].substring(dot + 1);
                JsonNode node = byCode.get(code);
                if (node == null) {
                    continue;
                }
                MarketIndex mi = parseEmNode(node, item[0], item[1]);
                if (mi != null) {
                    out.add(mi);
                }
            }
        } catch (IOException e) {
            log.warn("[market] 海外指数返回体解析失败 err={} body={}", e.getMessage(),
                    body == null ? "" : body.substring(0, Math.min(200, body.length())));
            return Collections.emptyList();
        }
        return out;
    }

    /**
     * 取数失败：一分钟最多报一条。
     *
     * @param domestic true 报国内（新浪）那一边，false 报海外（东财）那一边
     */
    private void warnThrottled(boolean domestic, String url, long cost, IOException e) {
        long now = System.currentTimeMillis();
        long last = domestic ? domesticWarnAt : overseasWarnAt;
        if (now - last < WARN_THROTTLE_MS) {
            return;
        }
        if (domestic) {
            domesticWarnAt = now;
        } else {
            overseasWarnAt = now;
        }
        log.warn("[market] {}指数行情调用失败 url={} 耗时 {} ms err={}",
                domestic ? "国内" : "海外", url, cost, e.getMessage());
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
     * 展示名由调用方（OVERSEAS 那一行）给，不取接口里的 f14。
     * 海外指数没有成交额（f6 恒为 0），成交额位留空，前端据此不渲染那一行。
     */
    private MarketIndex parseEmNode(JsonNode node, String secid, String displayName) {
        BigDecimal point = emNumber(node.get("f2"));
        if (point == null || point.signum() <= 0) {
            // 点位为 0 / 无数据（东财用 "-" 表示）时不展示，与国内那半边同一口径
            return null;
        }
        return new MarketIndex(secid, displayName, point,
                emNumber(node.get("f4")), emNumber(node.get("f3")), null);
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
