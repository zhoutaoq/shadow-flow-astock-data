package com.stock.kline.service;

import com.stock.kline.model.PageResult;
import com.stock.kline.model.StockKlineDaily;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * waizao_stock_kline_daily 的后台管理（分页 / 单条增删改 / 区间删除 / 图数据）。
 *
 * <p>v1.3：K线的<b>落库</b>动作统一走 {@code KlineRepository#save}（抓取链路），
 * 这里只保留「库里已有数据的管理」—— 无数据库模式下K线现取现用、不落盘，
 * 也就没有可管理的数据，所以本类仍只在数据库模式装配。
 */
@Service
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class StockKlineService {

    private static final String TABLE = "waizao_stock_kline_daily";
    private static final String COLUMNS = "k.stock_code, k.market, k.trade_date, k.open_price, k.close_price,"
            + " k.high_price, k.low_price, k.volume, k.amount, k.pre_close, k.create_time, k.update_time";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate named;

    public StockKlineService(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate named) {
        this.jdbcTemplate = jdbcTemplate;
        this.named = named;
    }

    public PageResult<StockKlineDaily> page(String code, String market, String startDate, String endDate,
                                            int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();
        if (StringUtils.hasText(code)) {
            where.append(" AND k.stock_code = :code ");
            params.put("code", code.trim());
        }
        if (StringUtils.hasText(market)) {
            where.append(" AND k.market = :market ");
            params.put("market", market.trim());
        }
        if (StringUtils.hasText(startDate)) {
            where.append(" AND k.trade_date >= :startDate ");
            params.put("startDate", startDate.trim());
        }
        if (StringUtils.hasText(endDate)) {
            where.append(" AND k.trade_date <= :endDate ");
            params.put("endDate", endDate.trim());
        }

        Long total = named.queryForObject("SELECT COUNT(1) FROM " + TABLE + " k" + where, params, Long.class);
        params.put("offset", (long) Math.max(page - 1, 0) * size);
        params.put("limit", size);
        List<StockKlineDaily> rows = named.query(
                "SELECT " + COLUMNS + ", s.stock_name FROM " + TABLE + " k"
                        + " LEFT JOIN waizao_stock_list s ON s.stock_code = k.stock_code AND s.market = k.market"
                        + where
                        + " ORDER BY k.trade_date DESC, k.stock_code LIMIT :offset, :limit",
                params, new BeanPropertyRowMapper<>(StockKlineDaily.class));
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    /** 按业务主键（代码 + 交易日）查一条 */
    public StockKlineDaily findByKey(String code, String market, String tradeDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("market", StringUtils.hasText(market) ? market : "stock");
        params.put("d", tradeDate);
        List<StockKlineDaily> rows = named.query(
                "SELECT " + COLUMNS + " FROM " + TABLE + " k"
                        + " WHERE k.stock_code = :code AND k.market = :market AND k.trade_date = :d",
                params, new BeanPropertyRowMapper<>(StockKlineDaily.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 写入一条；已存在同（代码 + 交易日）则覆盖更新。
     *
     * @return 处理条数
     */
    public int upsertOne(StockKlineDaily k) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", k.getStockCode());
        p.put("market", StringUtils.hasText(k.getMarket()) ? k.getMarket() : "stock");
        p.put("d", k.getTradeDate());
        p.put("o", k.getOpenPrice());
        p.put("c", k.getClosePrice());
        p.put("h", k.getHighPrice());
        p.put("l", k.getLowPrice());
        p.put("v", k.getVolume());
        p.put("a", k.getAmount());
        p.put("pc", k.getPreClose());
        return named.update("INSERT INTO " + TABLE
                        + " (stock_code, market, trade_date, open_price, close_price, high_price, low_price,"
                        + " volume, amount, pre_close) "
                        + "VALUES (:code, :market, :d, :o, :c, :h, :l, :v, :a, :pc) "
                        + "ON DUPLICATE KEY UPDATE market = VALUES(market), open_price = VALUES(open_price),"
                        + " close_price = VALUES(close_price), high_price = VALUES(high_price),"
                        + " low_price = VALUES(low_price), volume = VALUES(volume), amount = VALUES(amount),"
                        + " pre_close = VALUES(pre_close)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(p));
    }

    /** 按业务主键更新价格字段 */
    public int update(StockKlineDaily k) {
        Map<String, Object> p = new HashMap<>();
        p.put("code", k.getStockCode());
        p.put("market", StringUtils.hasText(k.getMarket()) ? k.getMarket() : "stock");
        p.put("d", k.getTradeDate());
        p.put("o", k.getOpenPrice());
        p.put("c", k.getClosePrice());
        p.put("h", k.getHighPrice());
        p.put("l", k.getLowPrice());
        p.put("v", k.getVolume());
        p.put("a", k.getAmount());
        p.put("pc", k.getPreClose());
        return named.update("UPDATE " + TABLE + " SET market = :market, open_price = :o, close_price = :c,"
                        + " high_price = :h, low_price = :l, volume = :v, amount = :a, pre_close = :pc"
                        + " WHERE stock_code = :code AND trade_date = :d",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource(p));
    }

    /** 按业务主键删除一条 */
    public int delete(String code, String market, String tradeDate) {
        return jdbcTemplate.update("DELETE FROM " + TABLE
                        + " WHERE stock_code = ? AND market = ? AND trade_date = ?",
                code, StringUtils.hasText(market) ? market : "stock", tradeDate);
    }

    public int deleteByRange(String code, String market, String startDate, String endDate) {
        StringBuilder sql = new StringBuilder("DELETE FROM " + TABLE + " WHERE 1=1 ");
        List<Object> args = new ArrayList<>();
        if (StringUtils.hasText(code)) {
            sql.append(" AND stock_code = ? ");
            args.add(code.trim());
        }
        if (StringUtils.hasText(market)) {
            sql.append(" AND market = ? ");
            args.add(market.trim());
        }
        if (StringUtils.hasText(startDate)) {
            sql.append(" AND trade_date >= ? ");
            args.add(startDate.trim());
        }
        if (StringUtils.hasText(endDate)) {
            sql.append(" AND trade_date <= ? ");
            args.add(endDate.trim());
        }
        return jdbcTemplate.update(sql.toString(), args.toArray());
    }

    /** 某只股票已入库日K的日期跨度，供图上懒加载判断还有没有更早/更晚的数据 */
    public Map<String, Object> dateSpan(String code, String market) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("market", StringUtils.hasText(market) ? market : "stock");
        List<Map<String, Object>> rows = named.queryForList(
                "SELECT MIN(trade_date) AS min_date, MAX(trade_date) AS max_date, COUNT(1) AS cnt FROM " + TABLE
                        + " WHERE stock_code = :code AND market = :market",
                params);
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

    /** K 线图数据：按交易日升序 */
    public List<StockKlineDaily> listForChart(String code, String market, String startDate, String endDate) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("market", StringUtils.hasText(market) ? market : "stock");
        params.put("startDate", startDate);
        params.put("endDate", endDate);
        return named.query("SELECT trade_date, open_price, close_price, high_price, low_price, volume, amount,"
                        + " pre_close FROM " + TABLE
                        + " WHERE stock_code = :code AND market = :market"
                        + " AND trade_date BETWEEN :startDate AND :endDate ORDER BY trade_date ASC",
                params, new BeanPropertyRowMapper<>(StockKlineDaily.class));
    }

    /** 各股票已有数据条数统计 */
    public Map<String, Long> countGroupByCode() {
        Map<String, Long> result = new HashMap<>();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT stock_code, COUNT(1) AS cnt FROM " + TABLE + " GROUP BY stock_code");
        for (Map<String, Object> r : rows) {
            result.put(String.valueOf(r.get("stock_code")), ((Number) r.get("cnt")).longValue());
        }
        return result;
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM " + TABLE, Long.class);
        return c == null ? 0 : c;
    }
}
