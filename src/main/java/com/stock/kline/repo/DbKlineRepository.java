package com.stock.kline.repo;

import com.stock.kline.client.RawKline;
import com.stock.kline.client.WaizaoKlineClient;
import com.stock.kline.model.ApiCallLog;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.support.KlineWindow;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库模式：日K落 {@code waizao_stock_kline_daily}，读不到就先同步再读（查时更新）。
 *
 * <p>补数据只补缺失那一天而不是重拉整个区间 —— 供应商接口按调用次数计费。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbKlineRepository implements KlineRepository {

    private static final String KLINE = "waizao_stock_kline_daily";
    private static final String COLUMNS = "trade_date, open_price, close_price, high_price, low_price,"
            + " volume, amount, pre_close";

    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbcTemplate;
    private final WaizaoKlineClient client;

    public DbKlineRepository(NamedParameterJdbcTemplate named, JdbcTemplate jdbcTemplate,
                             WaizaoKlineClient client) {
        this.named = named;
        this.jdbcTemplate = jdbcTemplate;
        this.client = client;
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

    @Override
    public void prepareOne(String market, String code, String date) {
        List<RawKline> raw;
        try {
            raw = client.fetch(market, code, date, date, ApiCallLog.SCENE_BATCH);
        } catch (Exception e) {
            throw new IllegalStateException("同步 " + date + " 的日K数据失败: " + e.getMessage());
        }
        if (raw == null || raw.isEmpty()) {
            throw new IllegalStateException("同步 " + date + " 的日K数据失败: 供应商未返回数据");
        }
        save(raw);
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

    @Override
    public List<StockKlineDaily> list(String market, String code, String start, String end) {
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

    /**
     * 批量落库：同 (code, market, trade_date) 存在则覆盖更新。
     *
     * @return 处理条数（MySQL 批量返回 SUCCESS_NO_INFO(-2)，不能简单用 &gt;0 判断）
     */
    @Override
    public int save(List<RawKline> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String sql = "INSERT INTO " + KLINE
                + " (stock_code, market, trade_date, open_price, close_price, high_price, low_price,"
                + " volume, amount, pre_close) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                + "ON DUPLICATE KEY UPDATE open_price = VALUES(open_price), close_price = VALUES(close_price),"
                + " high_price = VALUES(high_price), low_price = VALUES(low_price), volume = VALUES(volume),"
                + " amount = VALUES(amount), pre_close = VALUES(pre_close)";
        int batchSize = 500;
        int processed = 0;
        for (int i = 0; i < rows.size(); i += batchSize) {
            List<RawKline> sub = rows.subList(i, Math.min(i + batchSize, rows.size()));
            List<Object[]> args = new ArrayList<>(sub.size());
            for (RawKline r : sub) {
                args.add(new Object[]{
                        r.getCode(),
                        StringUtils.hasText(r.getSymbol()) ? r.getSymbol() : "stock",
                        r.getTradeDate(),
                        r.getOpenPrice(),
                        r.getClosePrice(),
                        r.getHighPrice(),
                        r.getLowPrice(),
                        r.getVolume(),
                        r.getAmount(),
                        r.getPreClose()
                });
            }
            int[] res = jdbcTemplate.batchUpdate(sql, args);
            for (int x : res) {
                if (x != java.sql.Statement.EXECUTE_FAILED) {
                    processed++;
                }
            }
        }
        return processed;
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
