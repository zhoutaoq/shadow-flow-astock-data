package com.stock.kline.support;

import com.stock.kline.model.ApiCallLog;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.service.KlineFetchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 数据库模式：读日K表 waizao_stock_kline_daily，缺的日期先走同步流程补上 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbKlineSource implements KlineSource {

    private static final String KLINE = "waizao_stock_kline_daily";
    private static final String COLUMNS = "trade_date, open_price, close_price, high_price, low_price,"
            + " volume, amount, pre_close";

    private final NamedParameterJdbcTemplate named;
    private final KlineFetchService fetchService;

    public DbKlineSource(NamedParameterJdbcTemplate named, KlineFetchService fetchService) {
        this.named = named;
        this.fetchService = fetchService;
    }

    @Override
    public KlineWindow load(String market, String code, String start, String end, int warmup) {
        List<StockKlineDaily> pre = warmup > 0 ? loadBefore(code, market, start, warmup) : Collections.emptyList();
        List<StockKlineDaily> range = loadRange(code, market, start, end);
        KlineWindow w = new KlineWindow();
        w.getRows().addAll(pre);
        w.getRows().addAll(range);
        w.setPreSize(pre.size());
        return w;
    }

    @Override
    public List<String> missing(String market, String code, String start, String end) {
        List<String> miss = new ArrayList<>();
        if (!hasDate(code, market, start)) {
            miss.add(start);
        }
        if (!hasDate(code, market, end)) {
            miss.add(end);
        }
        return miss;
    }

    /**
     * 补某一天：按「单日区间」向供应商发一次请求。
     *
     * <p>只补缺失那一天，而不是把整个区间重拉一遍 —— 接口按调用次数计费。
     */
    @Override
    public void prepareOne(String market, String code, String date) {
        KlineFetchService.CodeResult cr;
        try {
            cr = fetchService.fetchOne(market, code, date, date, false, ApiCallLog.SCENE_BATCH);
        } catch (Exception e) {
            throw new IllegalStateException("同步 " + date + " 的日K数据失败: " + e.getMessage());
        }
        if (cr == null || !cr.isSuccess()) {
            throw new IllegalStateException("同步 " + date + " 的日K数据失败: "
                    + (cr == null ? "无返回" : cr.getMessage()));
        }
    }

    /** 已入库日K的日期跨度，供图上懒加载判断还有没有更早/更晚的数据 */
    @Override
    public Map<String, Object> span(String market, String code) {
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT MIN(trade_date) AS min_date, MAX(trade_date) AS max_date, COUNT(1) AS cnt FROM " + KLINE
                        + " WHERE stock_code = :code AND market = :market",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("market", market));
        Map<String, Object> data = new HashMap<>();
        if (rows.isEmpty() || rows.get(0).get("cnt") == null
                || ((Number) rows.get(0).get("cnt")).longValue() == 0) {
            data.put("minDate", null);
            data.put("maxDate", null);
            data.put("count", 0L);
            return data;
        }
        Map<String, Object> r = rows.get(0);
        data.put("minDate", r.get("min_date") == null ? null : String.valueOf(r.get("min_date")));
        data.put("maxDate", r.get("max_date") == null ? null : String.valueOf(r.get("max_date")));
        data.put("count", ((Number) r.get("cnt")).longValue());
        return data;
    }

    private boolean hasDate(String code, String market, String date) {
        Integer n = named.queryForObject("SELECT COUNT(1) FROM " + KLINE
                        + " WHERE stock_code = :code AND market = :market AND trade_date = :d",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("market", market)
                        .addValue("d", date), Integer.class);
        return n != null && n > 0;
    }

    /** 起始日之前的预热数据（正序返回） */
    private List<StockKlineDaily> loadBefore(String code, String market, String start, int warmup) {
        List<StockKlineDaily> rows = named.query("SELECT " + COLUMNS + " FROM " + KLINE
                        + " WHERE stock_code = :code AND market = :market AND trade_date < :start"
                        + " ORDER BY trade_date DESC LIMIT :lim",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("market", market)
                        .addValue("start", start)
                        .addValue("lim", warmup),
                new BeanPropertyRowMapper<>(StockKlineDaily.class));
        Collections.reverse(rows);
        return rows;
    }

    private List<StockKlineDaily> loadRange(String code, String market, String start, String end) {
        return named.query("SELECT " + COLUMNS + " FROM " + KLINE
                        + " WHERE stock_code = :code AND market = :market"
                        + " AND trade_date BETWEEN :start AND :end ORDER BY trade_date ASC",
                new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("market", market)
                        .addValue("start", start)
                        .addValue("end", end),
                new BeanPropertyRowMapper<>(StockKlineDaily.class));
    }
}
