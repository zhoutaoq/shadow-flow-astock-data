package com.stock.kline.service;

import com.stock.kline.client.RawKline;
import com.stock.kline.client.WaizaoKlineClient;
import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.ApiCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 日K线抓取服务。
 *
 * <p>两类模式：
 * <ul>
 *   <li>dryRun = true ：只调用供应商接口并渲染结果，不做落盘（调试页）</li>
 *   <li>dryRun = false：调用接口 + 落盘 waizao_stock_kline_daily</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class KlineFetchService {

    private static final Logger log = LoggerFactory.getLogger(KlineFetchService.class);
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final WaizaoKlineClient client;
    private final StockKlineService klineService;
    private final WaizaoProperties props;

    public KlineFetchService(WaizaoKlineClient client, StockKlineService klineService,
                             WaizaoProperties props) {
        this.client = client;
        this.klineService = klineService;
        this.props = props;
    }

    /** 抓取请求 */
    public static class FetchRequest {
        private List<String> codes;
        private String market = "stock";
        private String startDate;
        private String endDate;
        private boolean dryRun = true;

        public List<String> getCodes() {
            return codes;
        }

        public void setCodes(List<String> codes) {
            this.codes = codes;
        }

        public String getMarket() {
            return market;
        }

        public void setMarket(String market) {
            this.market = market;
        }

        public String getStartDate() {
            return startDate;
        }

        public void setStartDate(String startDate) {
            this.startDate = startDate;
        }

        public String getEndDate() {
            return endDate;
        }

        public void setEndDate(String endDate) {
            this.endDate = endDate;
        }

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }
    }

    /** 单只股票的执行结果 */
    public static class CodeResult {
        private String code;
        private String market;
        private int fetched;
        private int persisted;
        private boolean success = true;
        private String message;
        private List<String> requestUrls = new ArrayList<>();
        private List<RawKline> rows = new ArrayList<>();

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMarket() {
            return market;
        }

        public void setMarket(String market) {
            this.market = market;
        }

        public int getFetched() {
            return fetched;
        }

        public void setFetched(int fetched) {
            this.fetched = fetched;
        }

        public int getPersisted() {
            return persisted;
        }

        public void setPersisted(int persisted) {
            this.persisted = persisted;
        }

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public List<String> getRequestUrls() {
            return requestUrls;
        }

        public void setRequestUrls(List<String> requestUrls) {
            this.requestUrls = requestUrls;
        }

        public List<RawKline> getRows() {
            return rows;
        }

        public void setRows(List<RawKline> rows) {
            this.rows = rows;
        }
    }

    /** 整体执行结果 */
    public static class FetchResult {
        private boolean dryRun;
        private int codeCount;
        private int fetchedTotal;
        private int persistedTotal;
        private int failedCount;
        private long costMs;
        private List<CodeResult> details = new ArrayList<>();

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public int getCodeCount() {
            return codeCount;
        }

        public void setCodeCount(int codeCount) {
            this.codeCount = codeCount;
        }

        public int getFetchedTotal() {
            return fetchedTotal;
        }

        public void setFetchedTotal(int fetchedTotal) {
            this.fetchedTotal = fetchedTotal;
        }

        public int getPersistedTotal() {
            return persistedTotal;
        }

        public void setPersistedTotal(int persistedTotal) {
            this.persistedTotal = persistedTotal;
        }

        public int getFailedCount() {
            return failedCount;
        }

        public void setFailedCount(int failedCount) {
            this.failedCount = failedCount;
        }

        public long getCostMs() {
            return costMs;
        }

        public void setCostMs(long costMs) {
            this.costMs = costMs;
        }

        public List<CodeResult> getDetails() {
            return details;
        }

        public void setDetails(List<CodeResult> details) {
            this.details = details;
        }
    }

    public FetchResult fetch(FetchRequest req) {
        long begin = System.currentTimeMillis();
        FetchResult result = new FetchResult();
        result.setDryRun(req.isDryRun());

        validate(req);

        List<String> codes = normalizeCodes(req.getCodes());
        result.setCodeCount(codes.size());

        String market = StringUtils.hasText(req.getMarket()) ? req.getMarket().trim() : "stock";

        for (int i = 0; i < codes.size(); i++) {
            String code = codes.get(i);
            CodeResult cr = new CodeResult();
            cr.setCode(code);
            cr.setMarket(market);
            result.getDetails().add(cr);

            try {
                cr.getRequestUrls().add(client.buildUrl(market, code, req.getStartDate(), req.getEndDate()));
                fetchInto(market, code, req.getStartDate(), req.getEndDate(), req.isDryRun(), cr,
                        ApiCallLog.SCENE_BATCH);
                cr.setMessage("OK");
            } catch (Exception e) {
                cr.setSuccess(false);
                cr.setMessage(e.getMessage());
                log.warn("[waizao] fetch failed, code={}, market={}, msg={}", code, market, e.getMessage());
            }

            result.setFetchedTotal(result.getFetchedTotal() + cr.getFetched());
            result.setPersistedTotal(result.getPersistedTotal() + cr.getPersisted());
            if (!cr.isSuccess()) {
                result.setFailedCount(result.getFailedCount() + 1);
            }

            // 多股票之间限速
            long interval = props.getApi().getBatchIntervalMs();
            if (i < codes.size() - 1 && interval > 0) {
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        result.setCostMs(System.currentTimeMillis() - begin);
        return result;
    }

    /** 单只股票：一次请求取回整个日期区间，按 dryRun 决定是否落盘 */
    public void fetchInto(String market, String code, String startDate, String endDate,
                          boolean dryRun, CodeResult cr, String scene) throws IOException {
        List<RawKline> rows = client.fetch(market, code, startDate, endDate, scene);
        cr.getRows().addAll(rows);
        cr.setFetched(cr.getRows().size());
        if (!dryRun && !rows.isEmpty()) {
            cr.setPersisted(klineService.upsertBatch(rows));
        }
    }

    /**
     * 单只股票抓取，返回独立结果（供并发任务调用）
     *
     * @param scene 调用场景，见 ApiCallLog.SCENE_DEV / SCENE_BATCH
     */
    public CodeResult fetchOne(String market, String code, String startDate, String endDate,
                               boolean dryRun, String scene) {
        CodeResult cr = new CodeResult();
        cr.setCode(code);
        cr.setMarket(market);
        try {
            cr.getRequestUrls().add(client.buildUrl(market, code, startDate, endDate));
            fetchInto(market, code, startDate, endDate, dryRun, cr, scene);
            cr.setMessage("OK");
        } catch (Exception e) {
            cr.setSuccess(false);
            cr.setMessage(e.getMessage());
            log.warn("[waizao] fetch failed, code={}, market={}, msg={}", code, market, e.getMessage());
        }
        return cr;
    }

    private void validate(FetchRequest req) {
        if (req.getCodes() == null || req.getCodes().isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        if (!StringUtils.hasText(req.getStartDate()) || !StringUtils.hasText(req.getEndDate())) {
            throw new IllegalArgumentException("开始日期和结束日期不能为空");
        }
        LocalDate start = LocalDate.parse(req.getStartDate().trim(), DF);
        LocalDate end = LocalDate.parse(req.getEndDate().trim(), DF);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
    }

    private List<String> normalizeCodes(List<String> codes) {
        List<String> list = new ArrayList<>();
        for (String c : codes) {
            if (!StringUtils.hasText(c)) {
                continue;
            }
            String v = c.trim();
            if (!list.contains(v)) {
                list.add(v);
            }
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        return list;
    }

    /**
     * 原始请求：一次HTTP调用回吐请求 URL、供应商原始响应体与解析结果，调用日志标记为 dev（调试）。
     *
     * @param persist 是否把解析结果落盘到 waizao_stock_kline_daily，由调试页开关控制
     */
    public Map<String, Object> rawQuery(String market, String code, String startDate, String endDate,
                                        boolean persist) throws Exception {
        WaizaoKlineClient.RawResult r = client.fetchRaw(market, code, startDate, endDate, ApiCallLog.SCENE_DEV);
        int persisted = 0;
        if (persist && r.getRows() != null && !r.getRows().isEmpty()) {
            persisted = klineService.upsertBatch(r.getRows());
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestUrl", r.getUrl());
        map.put("responseBody", r.getBody());
        map.put("parsed", r.getRows());
        map.put("persist", persist);
        map.put("persisted", persisted);
        return map;
    }
}
