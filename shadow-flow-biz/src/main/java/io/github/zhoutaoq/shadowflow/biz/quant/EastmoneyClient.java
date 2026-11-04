package io.github.zhoutaoq.shadowflow.biz.quant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 东方财富公开行情接口客户端（量化模块专用）。
 *
 * <p><b>三个必须写死在这里的工程结论，都是本机实测出来的</b>：
 * <ol>
 *   <li><b>强制 IPv4</b>：{@code push2his.eastmoney.com} 默认链路解析到 IPv6 时会
 *       「TCP 通、TLS 握手完成、随后零字节」，curl 报 HTTP 000；强制 IPv4 立刻 200。
 *       Java 的 HttpURLConnection 遇到这种半程失败不会自动回落，所以类加载时直接
 *       把 {@code java.net.preferIPv4Stack} 置为 true（调用方显式设过就不覆盖）。</li>
 *   <li><b>主域会被风控</b>：短时间连打多个不同接口会出现整片 HTTP 000，
 *       静置几十秒后自行恢复。所以每个请求都带退避重试，
 *       并且在主域连续失败时切到 {@code push2delay}。</li>
 *   <li><b>批量与单只的字段号不是一个体系</b>：{@code ulist.np} 的 f49/f71/f161/f191
 *       与 {@code stock/get} 的同名字段含义完全不同（实测 f49 在批量里是比例、单只里是外盘）。
 *       这里按接口分开解析，不做字段复用，避免拿到「看起来是数字」的错值。</li>
 * </ol>
 *
 * <p>本类只负责取数并把原始 JSON 收敛成项目对象，不做任何业务判断。
 */
@Component
public class EastmoneyClient {

    private static final Logger log = LoggerFactory.getLogger(EastmoneyClient.class);

    static {
        // 只在没人显式配置时才设：线上如果已经有 JVM 级配置，尊重它
        if (System.getProperty("java.net.preferIPv4Stack") == null) {
            System.setProperty("java.net.preferIPv4Stack", "true");
        }
    }

    private static final String[] QUOTE_HOSTS = {
            "https://push2.eastmoney.com",
            "https://push2delay.eastmoney.com"
    };
    private static final String[] HIS_HOSTS = {
            "https://push2his.eastmoney.com",
            "https://push2.eastmoney.com",
            "https://push2delay.eastmoney.com"
    };

    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";
    private static final String REFERER = "https://quote.eastmoney.com/";

    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 6_000;
    private static final int MAX_TRY_PER_HOST = 2;

    /** 单只补全字段的缓存时长：同一轮决策里可能多次问到同一只 */
    private static final long DETAIL_TTL_MS = 3_000L;

    private final ObjectMapper mapper = new ObjectMapper();

    /** code → 单只补全结果（委比/内外盘） */
    private final Map<String, CachedDetail> detailCache = new ConcurrentHashMap<>();

    /** code → 当日日K缓存（按日期失效，0 点后自然换新） */
    private final Map<String, CachedBars> barsCache = new ConcurrentHashMap<>();

    /** code → 资金流历史缓存（同上，日级） */
    private final Map<String, CachedFlow> flowCache = new ConcurrentHashMap<>();

    /** code → 基本面 / 一致预期分（季报与研报一天只变一次，按日期缓存） */
    private final Map<String, CachedScore> scoreCache = new ConcurrentHashMap<>();

    /* ==================== 快照 ==================== */

    /**
     * 批量快照。一次请求拿全部标的，盘中轮询只走这一条。
     *
     * @return code → 快照；取不到的标的不在返回里（调用方按缺失处理，不要拿 0 顶上）
     */
    public Map<String, QuantQuote> snapshots(List<String> codes) {
        Map<String, QuantQuote> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        List<String> distinct = new ArrayList<>();
        for (String c : codes) {
            String code = normalize(c);
            if (!code.isEmpty() && !distinct.contains(code)) {
                distinct.add(code);
            }
        }
        if (distinct.isEmpty()) {
            return out;
        }
        // 一次请求不宜过长，按 50 只分批
        for (int from = 0; from < distinct.size(); from += 50) {
            List<String> batch = distinct.subList(from, Math.min(from + 50, distinct.size()));
            StringBuilder secids = new StringBuilder();
            for (String code : batch) {
                if (secids.length() > 0) {
                    secids.append(',');
                }
                secids.append(secidOf(code));
            }
            String path = "/api/qt/ulist.np/get?fltt=2&secids=" + secids
                    + "&fields=f2,f3,f4,f5,f6,f8,f10,f12,f14,f15,f16,f17,f18,f20,f21,f62";
            JsonNode root = getJson(QUOTE_HOSTS, path);
            if (root == null) {
                continue;
            }
            JsonNode diff = root.path("data").path("diff");
            for (JsonNode n : diff) {
                QuantQuote q = parseUlist(n);
                if (q != null) {
                    out.put(q.getCode(), q);
                }
            }
        }
        return out;
    }

    /**
     * 单只补全：委比、内外盘、均价。
     *
     * <p>只对「持仓 + 候选」调，不对全池调 —— 这是权重 7% 的两项，
     * 值不值得多花请求，取决于这只票已经走到哪一层。
     */
    public QuantQuote detail(String code) {
        String norm = normalize(code);
        if (norm.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        CachedDetail cached = detailCache.get(norm);
        if (cached != null && now - cached.at < DETAIL_TTL_MS) {
            return cached.quote;
        }
        String path = "/api/qt/stock/get?fltt=2&secid=" + secidOf(norm)
                + "&fields=f43,f47,f48,f49,f50,f71,f161,f168,f170,f191,f192";
        JsonNode root = getJson(QUOTE_HOSTS, path);
        if (root == null) {
            return cached == null ? null : cached.quote;
        }
        JsonNode d = root.path("data");
        if (d.isMissingNode() || d.isNull()) {
            return cached == null ? null : cached.quote;
        }
        QuantQuote q = new QuantQuote();
        q.setCode(norm);
        q.setPrice(dec(d, "f43"));
        q.setVolume(dec(d, "f47"));
        q.setAmount(dec(d, "f48"));
        q.setOuterVolume(dec(d, "f49"));
        q.setVolumeRatio(dec(d, "f50"));
        q.setAvgPrice(dec(d, "f71"));
        q.setInnerVolume(dec(d, "f161"));
        q.setTurnoverRate(dec(d, "f168"));
        q.setChangePct(dec(d, "f170"));
        q.setWbRatio(dec(d, "f191"));
        q.setFetchedAt(now);
        detailCache.put(norm, new CachedDetail(q, now));
        return q;
    }

    private QuantQuote parseUlist(JsonNode n) {
        String code = text(n, "f12");
        if (code == null || code.isEmpty()) {
            return null;
        }
        BigDecimal price = dec(n, "f2");
        if (price == null || price.signum() <= 0) {
            // 停牌 / 未开盘：不参与决策，也不拿昨收冒充现价
            return null;
        }
        QuantQuote q = new QuantQuote();
        q.setCode(code);
        q.setName(text(n, "f14"));
        q.setPrice(price);
        q.setChangePct(nz(dec(n, "f3")));
        q.setChangeAmt(nz(dec(n, "f4")));
        q.setVolume(nz(dec(n, "f5")));
        q.setAmount(nz(dec(n, "f6")));
        q.setTurnoverRate(nz(dec(n, "f8")));
        q.setVolumeRatio(nz(dec(n, "f10")));
        q.setHigh(nz(dec(n, "f15")));
        q.setLow(nz(dec(n, "f16")));
        q.setOpen(nz(dec(n, "f17")));
        q.setPreClose(nz(dec(n, "f18")));
        q.setTotalMv(nz(dec(n, "f20")));
        q.setFloatMv(nz(dec(n, "f21")));
        q.setMainNetInflow(nz(dec(n, "f62")));
        q.setAvgPrice(avgOf(q));
        q.setFetchedAt(System.currentTimeMillis());
        return q;
    }

    /** 均价 = 成交额 ÷ (成交量 × 100)：成交量单位是「手」 */
    private static BigDecimal avgOf(QuantQuote q) {
        if (q.getVolume() == null || q.getVolume().signum() <= 0 || q.getAmount() == null) {
            return BigDecimal.ZERO;
        }
        return q.getAmount().divide(q.getVolume().multiply(BigDecimal.valueOf(100)), 4,
                BigDecimal.ROUND_HALF_UP);
    }

    /* ==================== 资金流 ==================== */

    /**
     * 资金流：日级明细（含当天那根），近 N 个交易日的 5/10/20 日累计都从这一份里算。
     *
     * <p><b>不单独拉分钟级</b>：日级接口最后一根就是当天实时累计，
     * 而「今日主力」批量快照的 f62 已经给了（实测与资金流接口一致）。
     * 两只接口各拉一次，等于每只票每分钟多一次请求，换来的是同一份数据。
     */
    public MainFlow mainFlow(String code) {
        String norm = normalize(code);
        if (norm.isEmpty()) {
            return null;
        }
        String today = java.time.LocalDate.now().toString();
        CachedFlow cached = flowCache.get(norm);
        if (cached != null && today.equals(cached.date)) {
            return cached.flow;
        }
        MainFlow flow = new MainFlow();
        flow.setCode(norm);
        flow.setDailyBars(loadDailyFlow(norm));
        if (flow.getDailyBars().isEmpty()) {
            return cached == null ? null : cached.flow;
        }
        flowCache.put(norm, new CachedFlow(flow, today));
        return flow;
    }

    /** 今日分钟级资金流：取最后一分钟行（当日累计） */
    private FlowRow loadTodayFlow(String code) {
        String path = "/api/qt/stock/fflow/kline/get?lmt=0&klt=1&secid=" + secidOf(code)
                + "&fields1=f1,f2,f3,f7&fields2=f51,f52,f53,f54,f55,f56";
        JsonNode root = getJson(QUOTE_HOSTS, path);
        if (root == null) {
            return null;
        }
        JsonNode klines = root.path("data").path("klines");
        FlowRow last = null;
        for (JsonNode k : klines) {
            FlowRow row = parseFlowRow(k.asText());
            if (row != null) {
                last = row;
            }
        }
        return last;
    }

    /** 日级资金流历史：一次拿半年，5/10/20 日都在里面 */
    private List<FlowRow> loadDailyFlow(String code) {
        String path = "/api/qt/stock/fflow/daykline/get?lmt=0&klt=101&secid=" + secidOf(code)
                + "&fields1=f1,f2,f3,f7&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";
        JsonNode root = getJson(HIS_HOSTS, path);
        if (root == null) {
            return Collections.emptyList();
        }
        List<FlowRow> out = new ArrayList<>();
        for (JsonNode k : root.path("data").path("klines")) {
            FlowRow row = parseFlowRow(k.asText());
            if (row != null) {
                out.add(row);
            }
        }
        return out;
    }

    /**
     * 一行资金流：{@code 日期,主力,小单,中单,大单,超大单,主力占比,...}。
     * 主力 = 大单 + 超大单，实测自洽（长电科技 0.36 亿 + 1.84 亿 = 2.21 亿）。
     */
    private FlowRow parseFlowRow(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] f = line.split(",");
        if (f.length < 6) {
            return null;
        }
        FlowRow row = new FlowRow();
        row.date = f[0].length() >= 10 ? f[0].substring(0, 10) : f[0];
        row.main = dec(f[1]);
        row.small = dec(f[2]);
        row.medium = dec(f[3]);
        row.big = dec(f[4]);
        row.jumbo = dec(f[5]);
        return row;
    }

    /* ==================== 日K ==================== */

    /**
     * 日K（前复权），最多 250 根 ≈ 一年，够算 52 周高低、均线、MACD、ATR。
     *
     * <p>按日缓存：这些指标一天只变一次，盘中反复拉没有意义。
     */
    public List<DailyBar> dailyBars(String code) {
        String norm = normalize(code);
        if (norm.isEmpty()) {
            return Collections.emptyList();
        }
        String today = java.time.LocalDate.now().toString();
        CachedBars cached = barsCache.get(norm);
        if (cached != null && today.equals(cached.date)) {
            return cached.bars;
        }
        List<DailyBar> out = barsBySecid(secidOf(norm), 280);
        if (!out.isEmpty() || cached == null) {
            barsCache.put(norm, new CachedBars(out, today));
        }
        return out;
    }

    /**
     * 指数日K：{@code secid} 要写完整（上证 {@code 1.000001}、深成 {@code 0.399001}、创业板 {@code 0.399006}）。
     *
     * <p>不能走 {@link #dailyBars(String)}：那里按「6 开头归沪市」推断，
     * 上证指数 000001 会被推成深市 000001（平安银行），直接算错 52 周低点。
     */
    public List<DailyBar> indexBars(String secid) {
        String key = "idx:" + secid;
        String today = java.time.LocalDate.now().toString();
        CachedBars cached = barsCache.get(key);
        if (cached != null && today.equals(cached.date)) {
            return cached.bars;
        }
        List<DailyBar> out = barsBySecid(secid, 280);
        if (out.isEmpty()) {
            // 东财历史接口会间歇性被风控（同一台机器几十秒内多次请求就整片 000），
            // 指数日K只用来算「距 52 周低」，新浪这条既稳又免费，直接做兜底
            out = sinaIndexBars(secid);
        }
        if (!out.isEmpty() || cached == null) {
            barsCache.put(key, new CachedBars(out, today));
        }
        return out;
    }

    /** 新浪指数日线兜底：1.000001 → sh000001，0.399001 → sz399001 */
    private List<DailyBar> sinaIndexBars(String secid) {
        String symbol = secid.startsWith("1.") ? "sh" + secid.substring(2) : "sz" + secid.substring(2);
        String url = "https://money.finance.sina.com.cn/quotes_service/api/json_v2.php/CN_MarketData"
                + ".getKLineData?symbol=" + symbol + "&scale=240&ma=no&datalen=280";
        List<DailyBar> out = new ArrayList<>();
        try {
            String body = doGet(url);
            JsonNode arr = mapper.readTree(body);
            for (JsonNode n : arr) {
                DailyBar b = new DailyBar();
                b.date = text(n, "day");
                b.open = dec(n, "open");
                b.close = dec(n, "close");
                b.high = dec(n, "high");
                b.low = dec(n, "low");
                b.volume = dec(n, "volume");
                if (b.close != null && b.close.signum() > 0) {
                    out.add(b);
                }
            }
        } catch (Exception e) {
            log.debug("[quant] 新浪指数日K兜底失败 {}：{}", symbol, e.getMessage());
        }
        return out;
    }

    private List<DailyBar> barsBySecid(String secid, int limit) {
        String path = "/api/qt/stock/kline/get?secid=" + secid
                + "&klt=101&fqt=1&lmt=" + limit + "&end=20500101"
                + "&fields1=f1,f2,f3,f4,f5,f6&fields2=f51,f52,f53,f54,f55,f56,f57,f58,f59,f60,f61";
        JsonNode root = getJson(HIS_HOSTS, path);
        List<DailyBar> out = new ArrayList<>();
        if (root != null) {
            for (JsonNode k : root.path("data").path("klines")) {
                DailyBar bar = parseBar(k.asText());
                if (bar != null) {
                    out.add(bar);
                }
            }
        }
        return out;
    }

    /**
     * 一根日K：{@code 日期,开,收,高,低,量(手),额,振幅,涨跌幅,涨跌额,换手率}。
     *
     * <p>注意顺序是「开、收、高、低」，不是常见的 OHLC —— 照 OHLC 读会把收盘价当成最高价。
     */
    private DailyBar parseBar(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        String[] f = line.split(",");
        if (f.length < 11) {
            return null;
        }
        DailyBar b = new DailyBar();
        b.date = f[0].length() >= 10 ? f[0].substring(0, 10) : f[0];
        b.open = dec(f[1]);
        b.close = dec(f[2]);
        b.high = dec(f[3]);
        b.low = dec(f[4]);
        b.volume = dec(f[5]);
        b.amount = dec(f[6]);
        b.amplitude = dec(f[7]);
        b.changePct = dec(f[8]);
        b.changeAmt = dec(f[9]);
        b.turnoverRate = dec(f[10]);
        return b;
    }

    /* ==================== 基本面 / 机构预期 ==================== */

    /**
     * 基本面分（0-100）：营收增速、净利增速、毛利率。
     *
     * <p>只取「扛不扛得住」相关的几项，不做到估值模型 —— 这一项权重只有 3%，
     * 它决定的是「这只票能不能拿住」，不是「什么时候买」。
     *
     * @return null 表示取不到（调用方按中性 50 处理，不要当 0 分）
     */
    public Integer financialScore(String code) {
        String norm = normalize(code);
        if (norm.isEmpty()) {
            return null;
        }
        String key = "fin:" + norm;
        String today = java.time.LocalDate.now().toString();
        CachedScore cached = scoreCache.get(key);
        if (cached != null && today.equals(cached.date)) {
            return cached.score;
        }
        String path = "/securities/api/data/v1/get?reportName=RPT_F10_FINANCE_MAINFINADATA"
                + "&columns=ALL&filter=(SECUCODE%3D%22" + secucodeOf(norm) + "%22)"
                + "&pageNumber=1&pageSize=1&sortTypes=-1&sortColumns=REPORT_DATE&source=HSF10&client=PC";
        JsonNode root = getJson(new String[]{"https://datacenter.eastmoney.com"}, path);
        Integer score = null;
        if (root != null) {
            JsonNode row = root.path("result").path("data").path(0);
            if (!row.isMissingNode() && !row.isNull()) {
                BigDecimal revTz = dec(row, "TOTALOPERATEREVETZ");
                BigDecimal profitTz = dec(row, "PARENTNETPROFITTZ");
                BigDecimal revenue = dec(row, "TOTALOPERATEREVE");
                BigDecimal gross = dec(row, "MLR");
                int s = 50;
                if (revTz != null) {
                    double v = revTz.doubleValue();
                    s += v >= 20 ? 15 : v >= 10 ? 10 : v >= 0 ? 3 : -12;
                }
                if (profitTz != null) {
                    double v = profitTz.doubleValue();
                    s += v >= 30 ? 20 : v >= 10 ? 12 : v >= 0 ? 4 : -15;
                }
                if (revenue != null && revenue.signum() > 0 && gross != null) {
                    double gm = gross.doubleValue() / revenue.doubleValue() * 100;
                    s += gm >= 30 ? 10 : gm >= 15 ? 5 : -5;
                }
                score = Math.max(0, Math.min(100, s));
            }
        }
        if (score != null || cached == null) {
            scoreCache.put(key, new CachedScore(score, today));
        }
        return score != null ? score : (cached == null ? null : cached.score);
    }

    /**
     * 机构预期分（0-100）：按研报里「明年 EPS / 今年 EPS」的增速给分。
     *
     * <p>不用目标价：目标价的字段口径各家不一样，而且经常被下调，
     * 拿来做 2% 权重的打分不如直接用盈利预测增速来得稳定。
     *
     * @return null 表示取不到
     */
    public Integer estimateScore(String code) {
        String norm = normalize(code);
        if (norm.isEmpty()) {
            return null;
        }
        String key = "est:" + norm;
        String today = java.time.LocalDate.now().toString();
        CachedScore cached = scoreCache.get(key);
        if (cached != null && today.equals(cached.date)) {
            return cached.score;
        }
        String begin = java.time.LocalDate.now().minusMonths(6).toString();
        String end = java.time.LocalDate.now().toString();
        String url = "https://reportapi.eastmoney.com/report/list?industryCode=*&pageSize=20&industry=*"
                + "&beginTime=" + begin + "&endTime=" + end + "&pageNo=1&qType=0&code=" + norm;
        Integer score = null;
        try {
            JsonNode root = mapper.readTree(doGet(url));
            int count = 0;
            double sumGrowth = 0;
            for (JsonNode rep : root.path("data")) {
                BigDecimal thisYear = dec(rep, "predictThisYearEps");
                BigDecimal nextYear = dec(rep, "predictNextYearEps");
                if (thisYear == null || nextYear == null
                        || thisYear.signum() <= 0 || nextYear.signum() <= 0) {
                    continue;
                }
                sumGrowth += (nextYear.doubleValue() / thisYear.doubleValue() - 1) * 100;
                count++;
            }
            if (count > 0) {
                double avg = sumGrowth / count;
                int s = 50;
                s += avg >= 40 ? 25 : avg >= 30 ? 20 : avg >= 15 ? 12 : avg >= 0 ? 3 : -15;
                score = Math.max(0, Math.min(100, s));
            }
        } catch (Exception e) {
            log.debug("[quant] 一致预期取数失败 {}：{}", norm, e.getMessage());
        }
        if (score != null || cached == null) {
            scoreCache.put(key, new CachedScore(score, today));
        }
        return score != null ? score : (cached == null ? null : cached.score);
    }

    /** 600584 → 600584.SH；000001 → 000001.SZ */
    private static String secucodeOf(String code) {
        String c = normalize(code);
        if (c.length() != 6) {
            return c;
        }
        char first = c.charAt(0);
        return c + ((first == '6' || first == '9') ? ".SH" : ".SZ");
    }

    /* ==================== 板块 ==================== */

    /**
     * 全市场行业板块的资金流排名（按主力净额降序）。
     *
     * <p>只取前 100 个：判断「板块宽度」看的是净流入板块占比与头部强度，
     * 后段几十个板块的绝对值都很小，多取无益。
     */
    public List<SectorFlow> sectorFlows() {
        String path = "/api/qt/clist/get?pn=1&pz=100&po=1&np=1&fltt=2&invt=2&fid=f62&fs=m:90+t:2"
                + "&fields=f12,f14,f3,f62,f184";
        JsonNode root = getJson(QUOTE_HOSTS, path);
        List<SectorFlow> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        for (JsonNode n : root.path("data").path("diff")) {
            SectorFlow s = new SectorFlow();
            s.code = text(n, "f12");
            s.name = text(n, "f14");
            s.changePct = dec(n, "f3");
            s.mainNetInflow = dec(n, "f62");
            s.mainNetPct = dec(n, "f184");
            out.add(s);
        }
        return out;
    }

    /* ==================== HTTP ==================== */

    private JsonNode getJson(String[] hosts, String path) {
        for (String host : hosts) {
            for (int attempt = 1; attempt <= MAX_TRY_PER_HOST; attempt++) {
                try {
                    String body = doGet(host + path);
                    JsonNode root = mapper.readTree(body);
                    if (root != null && root.path("rc").asInt(0) == 0) {
                        return root;
                    }
                    log.debug("[quant] {} 返回非 0 rc，换下一个尝试", host);
                } catch (Exception e) {
                    log.debug("[quant] {} 第 {} 次失败：{}", host, attempt, e.getMessage());
                    if (attempt < MAX_TRY_PER_HOST) {
                        sleep(200L * attempt);
                    }
                }
            }
        }
        return null;
    }

    private String doGet(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
        conn.setRequestProperty("Referer", REFERER);
        conn.setRequestProperty("User-Agent", UA);
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
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ==================== 工具 ==================== */

    /** 600584 → 1.600584；000001 → 0.000001。沪市 6/9 开头，其余归深市 */
    public static String secidOf(String code) {
        String c = normalize(code);
        if (c.length() != 6) {
            return "0." + c;
        }
        char first = c.charAt(0);
        return (first == '6' || first == '9') ? "1." + c : "0." + c;
    }

    /** 去掉 sh/sz 前缀与空白，只留 6 位数字 */
    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        String s = code.trim().toLowerCase();
        if (s.startsWith("sh") || s.startsWith("sz")) {
            s = s.substring(2);
        }
        return s.replaceAll("[^0-9]", "");
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static BigDecimal dec(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        // 东财无数据时给字符串 "-"
        String s = v.asText();
        if (s == null || s.isEmpty() || "-".equals(s.trim())) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal dec(String s) {
        if (s == null || s.trim().isEmpty() || "-".equals(s.trim())) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    /* ==================== 内部数据结构 ==================== */

    private static final class CachedDetail {
        final QuantQuote quote;
        final long at;

        CachedDetail(QuantQuote quote, long at) {
            this.quote = quote;
            this.at = at;
        }
    }

    private static final class CachedBars {
        final List<DailyBar> bars;
        final String date;

        CachedBars(List<DailyBar> bars, String date) {
            this.bars = bars;
            this.date = date;
        }
    }

    private static final class CachedFlow {
        final MainFlow flow;
        final String date;

        CachedFlow(MainFlow flow, String date) {
            this.flow = flow;
            this.date = date;
        }
    }

    /** 基本面 / 一致预期的打分缓存；score 为 null 表示当天确实取不到 */
    private static final class CachedScore {
        final Integer score;
        final String date;

        CachedScore(Integer score, String date) {
            this.score = score;
            this.date = date;
        }
    }

    /** 一根日K */
    public static class DailyBar {
        public String date;
        public BigDecimal open;
        public BigDecimal close;
        public BigDecimal high;
        public BigDecimal low;
        /** 成交量（手） */
        public BigDecimal volume;
        public BigDecimal amount;
        public BigDecimal amplitude;
        public BigDecimal changePct;
        public BigDecimal changeAmt;
        public BigDecimal turnoverRate;
    }

    /** 一行资金流 */
    public static class FlowRow {
        public String date;
        /** 主力净额（元） */
        public BigDecimal main;
        public BigDecimal small;
        public BigDecimal medium;
        public BigDecimal big;
        /** 超大单净额（元） */
        public BigDecimal jumbo;
    }

    /** 资金流汇总 */
    public static class MainFlow {
        private String code;
        /** 今日分钟级最新一行（当日累计） */
        private FlowRow today;
        /** 日级历史，时间升序 */
        private List<FlowRow> dailyBars = new ArrayList<>();

        /** 近 N 个交易日主力净额合计（不含今日，今日单独给） */
        public BigDecimal sumMain(int days) {
            return sum(days, true);
        }

        /** 近 N 个交易日超大单净额合计 */
        public BigDecimal sumJumbo(int days) {
            return sum(days, false);
        }

        private BigDecimal sum(int days, boolean main) {
            if (days <= 0) {
                return BigDecimal.ZERO;
            }
            BigDecimal total = BigDecimal.ZERO;
            int from = Math.max(0, dailyBars.size() - days);
            for (int i = from; i < dailyBars.size(); i++) {
                FlowRow r = dailyBars.get(i);
                BigDecimal v = main ? r.main : r.jumbo;
                if (v != null) {
                    total = total.add(v);
                }
            }
            return total;
        }

        /** 今日主力净额：优先分钟级最新值，没有则退回日级最后一行 */
        public BigDecimal todayMain() {
            if (today != null && today.main != null) {
                return today.main;
            }
            if (!dailyBars.isEmpty()) {
                FlowRow last = dailyBars.get(dailyBars.size() - 1);
                return last.main == null ? BigDecimal.ZERO : last.main;
            }
            return BigDecimal.ZERO;
        }

        /** 今日超大单净额 */
        public BigDecimal todayJumbo() {
            if (today != null && today.jumbo != null) {
                return today.jumbo;
            }
            if (!dailyBars.isEmpty()) {
                FlowRow last = dailyBars.get(dailyBars.size() - 1);
                return last.jumbo == null ? BigDecimal.ZERO : last.jumbo;
            }
            return BigDecimal.ZERO;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public FlowRow getToday() {
            return today;
        }

        public void setToday(FlowRow today) {
            this.today = today;
        }

        public List<FlowRow> getDailyBars() {
            return dailyBars;
        }

        public void setDailyBars(List<FlowRow> dailyBars) {
            this.dailyBars = dailyBars == null ? new ArrayList<>() : dailyBars;
        }
    }

    /** 板块一行 */
    public static class SectorFlow {
        public String code;
        public String name;
        public BigDecimal changePct;
        /** 主力净额（元） */
        public BigDecimal mainNetInflow;
        /** 主力净占比（%） */
        public BigDecimal mainNetPct;
    }
}
