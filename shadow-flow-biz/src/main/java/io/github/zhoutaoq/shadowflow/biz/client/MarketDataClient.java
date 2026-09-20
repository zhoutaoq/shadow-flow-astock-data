package io.github.zhoutaoq.shadowflow.biz.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhoutaoq.shadowflow.biz.config.MarketDataProperties;
import io.github.zhoutaoq.shadowflow.biz.model.ApiCallLog;
import io.github.zhoutaoq.shadowflow.biz.repo.ApiCallLogRepository;
import io.github.zhoutaoq.shadowflow.biz.support.TokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 行情数据服务接口客户端（日K线）。
 *
 * <p>接口：GET {baseUrl}/market/c/daykline
 * 参数：symbol / code / period / dividend / startDate / endDate / token
 * 说明：响应体为 gzip 压缩的 JSON 数组，需要按 Content-Encoding 解压后解析。
 */
@Component
public class MarketDataClient {

    private static final Logger log = LoggerFactory.getLogger(MarketDataClient.class);

    private final MarketDataProperties props;
    private final TokenProvider tokenProvider;
    private final ApiCallLogRepository callLogRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MarketDataClient(MarketDataProperties props, TokenProvider tokenProvider,
                             ApiCallLogRepository callLogRepository) {
        this.props = props;
        this.tokenProvider = tokenProvider;
        this.callLogRepository = callLogRepository;
    }

    /**
     * 单只股票、单段日期区间的日K线查询，返回行情数据服务原始行（未落盘）
     *
     * @param scene 调用场景，见 ApiCallLog.SCENE_DEV / SCENE_BATCH
     */
    public List<RawKline> fetch(String symbol, String code, String startDate, String endDate, String scene)
            throws IOException {
        String url = buildUrl(symbol, code, startDate, endDate);
        long begin = System.currentTimeMillis();
        int status = 0;
        int rows = 0;
        String err = null;
        try {
            checkEnabled();
            HttpResult r = doGet(url);
            status = r.getStatus();
            if (status >= 400) {
                throw new IOException("HTTP " + status + ", body=" + r.getBody());
            }
            List<RawKline> list = parse(r.getBody(), symbol, code);
            rows = list.size();
            log.info("[market-data] fetch scene={} symbol={} code={} range=[{} ~ {}] status={} rows={} cost={}ms",
                    scene, symbol, code, startDate, endDate, status, rows, System.currentTimeMillis() - begin);
            return list;
        } catch (IOException | RuntimeException e) {
            err = e.getMessage();
            throw e;
        } finally {
            recordLog(scene, symbol, code, startDate, endDate, begin, status, rows, err);
        }
    }

    /**
     * 原始请求：一次HTTP调用同时拿到 URL、状态码、响应原文与解析结果，并记录调用日志。
     * 调试页用它，避免为同一份数据发两次请求。
     *
     * @param scene 调用场景，调试页传 ApiCallLog.SCENE_DEV
     */
    public RawResult fetchRaw(String symbol, String code, String startDate, String endDate, String scene)
            throws IOException {
        String url = buildUrl(symbol, code, startDate, endDate);
        long begin = System.currentTimeMillis();
        int status = 0;
        int rows = 0;
        String err = null;
        try {
            checkEnabled();
            HttpResult r = doGet(url);
            status = r.getStatus();
            if (status >= 400) {
                throw new IOException("HTTP " + status + ", body=" + r.getBody());
            }
            List<RawKline> list = parse(r.getBody(), symbol, code);
            rows = list.size();
            return new RawResult(url, r.getBody(), list);
        } catch (IOException | RuntimeException e) {
            err = e.getMessage();
            throw e;
        } finally {
            recordLog(scene, symbol, code, startDate, endDate, begin, status, rows, err);
        }
    }

    /**
     * 行情数据服务接口被停用（application.yml 的 shadowflow.api.enabled=false）时拒绝发请求。
     *
     * <p>这一步必须在发出 HTTP 之前拦住，否则"停用"只是摆设。
     */
    private void checkEnabled() throws IOException {
        if (!props.getApi().isEnabled()) {
            throw new IOException("行情数据服务接口已停用（shadowflow.api.enabled=false），未发起请求；"
                    + "如需恢复请改配置文件后重启");
        }
    }

    /** 写入调用日志：只记状态码与耗时，不记响应体 */
    private void recordLog(String scene, String symbol, String code, String startDate, String endDate,
                           long begin, int status, int rows, String err) {
        ApiCallLog item = new ApiCallLog();
        item.setScene(scene == null || scene.trim().isEmpty() ? ApiCallLog.SCENE_BATCH : scene.trim());
        item.setStockCode(code);
        item.setMarket(symbol);
        item.setReqStartDate(startDate);
        item.setReqEndDate(endDate);
        item.setHttpStatus(status);
        item.setCostMs((int) (System.currentTimeMillis() - begin));
        item.setRowCount(rows);
        item.setSuccess(err == null ? 1 : 0);
        item.setErrorMsg(err);
        callLogRepository.record(item, begin);
    }

    public String buildUrl(String symbol, String code, String startDate, String endDate) {
        String baseUrl = props.getApi().getBaseUrl();
        StringBuilder sb = new StringBuilder();
        sb.append(baseUrl).append("/market/c/daykline")
                .append("?symbol=").append(enc(symbol))
                .append("&code=").append(enc(code))
                .append("&period=1d")
                .append("&dividend=1")
                .append("&startDate=").append(enc(startDate))
                .append("&endDate=").append(enc(endDate))
                // token 是会变的凭据：数据库模式取自配置表，无数据库模式取自请求头
                .append("&token=").append(enc(tokenProvider.token()));
        return sb.toString();
    }

    private static String enc(String value) {
        if (value == null) {
            return "";
        }
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    /**
     * 发起 GET，返回状态码与响应体。
     * 注意：4xx/5xx 不抛异常，由调用方根据 status 决定（响应体需要回吐给调试页）。
     */
    public HttpResult doGet(String urlStr) throws IOException {
        MarketDataProperties.Api api = props.getApi();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(api.getConnectTimeoutMs());
        conn.setReadTimeout(api.getReadTimeoutMs());
        conn.setRequestProperty("Accept", "application/json, text/plain, */*");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Origin", "http://www.waizaowang.com");
        conn.setRequestProperty("Referer", "http://www.waizaowang.com/");
        conn.setRequestProperty("User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36");

        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            throw new IOException("HTTP " + status + " 无响应体, url=" + urlStr);
        }
        boolean gzip = "gzip".equalsIgnoreCase(conn.getHeaderField("Content-Encoding"));
        try (InputStream in = gzip ? new GZIPInputStream(stream) : stream;
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            return new HttpResult(status, body);
        }
    }

    /** HTTP 状态码 + 响应体 */
    public static class HttpResult {
        private final int status;
        private final String body;

        public HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int getStatus() {
            return status;
        }

        public String getBody() {
            return body;
        }
    }

    /** 调试用：一次请求拿到的全部信息 */
    public static class RawResult {
        private final String url;
        private final String body;
        private final List<RawKline> rows;

        public RawResult(String url, String body, List<RawKline> rows) {
            this.url = url;
            this.body = body;
            this.rows = rows;
        }

        public String getUrl() {
            return url;
        }

        public String getBody() {
            return body;
        }

        public List<RawKline> getRows() {
            return rows;
        }
    }

    private List<RawKline> parse(String body, String symbol, String fallbackCode) throws IOException {
        List<RawKline> list = new ArrayList<>();
        if (body == null || body.trim().isEmpty()) {
            return list;
        }
        String trimmed = body.trim();
        JsonNode root = objectMapper.readTree(trimmed);
        // 行情数据服务异常时可能返回对象而非数组
        if (root != null && root.isObject()) {
            if (root.has("data") && root.get("data").isArray()) {
                root = root.get("data");
            } else {
                throw new IOException("接口返回异常: " + trimmed);
            }
        }
        if (root == null || !root.isArray()) {
            return list;
        }
        Iterator<JsonNode> it = root.elements();
        while (it.hasNext()) {
            JsonNode n = it.next();
            RawKline k = new RawKline();
            k.setSymbol(symbol);
            k.setCode(text(n, "code", fallbackCode));
            k.setTradeDate(text(n, "t", null));
            k.setOpenPrice(decimal(n, "o"));
            k.setClosePrice(decimal(n, "c"));
            k.setHighPrice(decimal(n, "h"));
            k.setLowPrice(decimal(n, "l"));
            k.setVolume(longVal(n, "v"));
            k.setAmount(decimal(n, "a"));
            k.setPreClose(decimal(n, "pc"));
            list.add(k);
        }
        return list;
    }

    private static String text(JsonNode n, String field, String def) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            return def;
        }
        String s = v.asText();
        if (s.length() > 10) {
            s = s.substring(0, 10);
        }
        return s;
    }

    private static BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.decimalValue();
    }

    private static Long longVal(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) {
            return null;
        }
        return v.decimalValue().longValue();
    }
}
