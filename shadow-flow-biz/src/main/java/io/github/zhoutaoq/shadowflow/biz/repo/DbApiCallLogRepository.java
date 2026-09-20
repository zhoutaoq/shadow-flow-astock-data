package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.ApiCallLog;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 数据库模式：调用日志落 {@code shadowflow_api_call_log} */
@Repository
@DependsOn("shadowFlowDbInitializer")
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbApiCallLogRepository implements ApiCallLogRepository {

    private static final Logger log = LoggerFactory.getLogger(DbApiCallLogRepository.class);

    private static final String TABLE = "shadowflow_api_call_log";

    private final NamedParameterJdbcTemplate named;

    public DbApiCallLogRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public boolean persistent() {
        return true;
    }

    /**
     * 记录一次接口调用；记录失败不得影响抓取主流程。
     */
    @Override
    public void record(ApiCallLog item, long requestBeginMillis) {
        if (item == null) {
            return;
        }
        try {
            named.update("INSERT INTO " + TABLE
                            + " (scene, stock_code, market, req_start_date, req_end_date, request_time,"
                            + " http_status, cost_ms, row_count, success, error_msg) "
                            + "VALUES (:scene, :code, :market, :sd, :ed, :rt, :status, :cost, :rows, :ok, :err)",
                    new MapSqlParameterSource()
                            .addValue("scene", normalizeScene(item.getScene()))
                            .addValue("code", nvl(item.getStockCode()))
                            .addValue("market", nvl(item.getMarket()))
                            .addValue("sd", blankToNull(item.getReqStartDate()))
                            .addValue("ed", blankToNull(item.getReqEndDate()))
                            .addValue("rt", new Timestamp(requestBeginMillis))
                            .addValue("status", item.getHttpStatus())
                            .addValue("cost", item.getCostMs())
                            .addValue("rows", item.getRowCount())
                            .addValue("ok", item.getSuccess() == null || item.getSuccess() != 1 ? 0 : 1)
                            .addValue("err", cut(item.getErrorMsg(), 500)));
        } catch (Exception e) {
            log.warn("[api-log] 写入调用日志失败: {}", e.getMessage());
        }
    }

    /**
     * 分页查询调用日志。
     * 日期参数（reqStartDate/reqEndDate）按"与传入区间有交集"筛选；请求时间按发生时间区间筛选。
     */
    @Override
    public PageResult<ApiCallLog> page(String code, String market, String scene,
                                       String reqStartDate, String reqEndDate,
                                       String requestTimeFrom, String requestTimeTo, Integer success,
                                       int page, int size) {
        StringBuilder where = new StringBuilder(" FROM " + TABLE + " l WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();

        if (StringUtils.hasText(code)) {
            where.append(" AND l.stock_code = :code ");
            params.put("code", code.trim());
        }
        if (StringUtils.hasText(market)) {
            where.append(" AND l.market = :market ");
            params.put("market", market.trim());
        }
        if (StringUtils.hasText(scene)) {
            where.append(" AND l.scene = :scene ");
            params.put("scene", normalizeScene(scene));
        }
        if (StringUtils.hasText(reqStartDate)) {
            where.append(" AND l.req_end_date >= :reqStartDate ");
            params.put("reqStartDate", reqStartDate.trim());
        }
        if (StringUtils.hasText(reqEndDate)) {
            where.append(" AND l.req_start_date <= :reqEndDate ");
            params.put("reqEndDate", reqEndDate.trim());
        }
        if (StringUtils.hasText(requestTimeFrom)) {
            where.append(" AND l.request_time >= :rtFrom ");
            params.put("rtFrom", normalizeTime(requestTimeFrom.trim(), false));
        }
        if (StringUtils.hasText(requestTimeTo)) {
            where.append(" AND l.request_time <= :rtTo ");
            params.put("rtTo", normalizeTime(requestTimeTo.trim(), true));
        }
        if (success != null) {
            where.append(" AND l.success = :success ");
            params.put("success", success);
        }

        Long total = named.queryForObject("SELECT COUNT(1) " + where, params, Long.class);
        params.put("offset", (long) Math.max(page - 1, 0) * size);
        params.put("limit", size);
        List<ApiCallLog> rows = named.query(
                "SELECT l.id, l.scene, l.stock_code, l.market, l.req_start_date, l.req_end_date,"
                        + " DATE_FORMAT(l.request_time, '%Y-%m-%d %H:%i:%s') AS request_time,"
                        + " l.http_status, l.cost_ms, l.row_count, l.success, l.error_msg "
                        + where
                        + " ORDER BY l.request_time DESC, l.id DESC LIMIT :offset, :limit",
                params, new BeanPropertyRowMapper<>(ApiCallLog.class));
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    /** 统计调用次数与耗时（用于日志页顶部概览） */
    @Override
    public Map<String, Object> summary() {
        Map<String, Object> data = new HashMap<>();
        Long total = named.queryForObject("SELECT COUNT(1) FROM " + TABLE,
                new MapSqlParameterSource(), Long.class);
        Long failed = named.queryForObject("SELECT COUNT(1) FROM " + TABLE + " WHERE success = 0",
                new MapSqlParameterSource(), Long.class);
        data.put("total", total == null ? 0L : total);
        data.put("failed", failed == null ? 0L : failed);
        data.put("success", (total == null ? 0L : total) - (failed == null ? 0L : failed));

        Double avgCost = named.queryForObject("SELECT IFNULL(AVG(cost_ms), 0) FROM " + TABLE,
                new MapSqlParameterSource(), Double.class);
        data.put("avgCostMs", avgCost == null ? 0 : Math.round(avgCost));

        Map<String, Object> byScene = new LinkedHashMap<>();
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT scene, COUNT(1) AS cnt, IFNULL(SUM(cost_ms), 0) AS cost FROM " + TABLE
                        + " GROUP BY scene", new MapSqlParameterSource());
        for (Map<String, Object> r : rows) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("count", ((Number) r.get("cnt")).longValue());
            one.put("totalCostMs", ((Number) r.get("cost")).longValue());
            byScene.put(String.valueOf(r.get("scene")), one);
        }
        data.put("byScene", byScene);
        return data;
    }

    /** 场景只认 dev / batch，其余按批处理兜底 */
    private static String normalizeScene(String scene) {
        if (!StringUtils.hasText(scene)) {
            return ApiCallLog.SCENE_BATCH;
        }
        String v = scene.trim().toLowerCase();
        return ApiCallLog.SCENE_DEV.equals(v) ? ApiCallLog.SCENE_DEV : ApiCallLog.SCENE_BATCH;
    }

    /** 请求时间支持 "yyyy-MM-dd" 与 "yyyy-MM-dd HH:mm[:ss]"；结束时间补到当日/当分末尾 */
    private static String normalizeTime(String v, boolean isEnd) {
        String s = v.replace('T', ' ').trim();
        if (s.length() == 10) {
            return isEnd ? s + " 23:59:59" : s + " 00:00:00";
        }
        if (s.length() == 16) {
            return isEnd ? s + ":59" : s + ":00";
        }
        return s;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return (s == null || s.trim().isEmpty()) ? null : s.trim();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String v = s.replace('\n', ' ').trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
