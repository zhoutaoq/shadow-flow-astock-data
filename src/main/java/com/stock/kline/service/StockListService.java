package com.stock.kline.service;

import com.stock.kline.model.PageResult;
import com.stock.kline.model.StockListItem;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** waizao_stock_list 增删改查 + json 导入 */
@Service
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class StockListService {

    private static final String TABLE = "waizao_stock_list";

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate named;

    public StockListService(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate named) {
        this.jdbcTemplate = jdbcTemplate;
        this.named = named;
    }

    public PageResult<StockListItem> page(String keyword, String market, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        Map<String, Object> params = new HashMap<>();
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (stock_code LIKE :kw OR stock_name LIKE :kw) ");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        if (StringUtils.hasText(market)) {
            where.append(" AND market = :market ");
            params.put("market", market.trim());
        }
        Long total = named.queryForObject("SELECT COUNT(1) FROM " + TABLE + where, params, Long.class);
        params.put("offset", (long) Math.max(page - 1, 0) * size);
        params.put("limit", size);
        List<StockListItem> rows = named.query(
                "SELECT id, stock_code, stock_name, market, enabled, create_time, update_time FROM "
                        + TABLE + where + " ORDER BY stock_code LIMIT :offset, :limit",
                params, new BeanPropertyRowMapper<>(StockListItem.class));
        return new PageResult<>(rows, total == null ? 0 : total, page, size);
    }

    /**
     * 按代码 / 名称模糊匹配，供页面股票选择器联想使用。
     *
     * <p>排序让"输入完整代码"的那只排在最前：完全相等 → 代码前缀匹配 → 其余，同档内按代码升序。
     *
     * @param limit 最多返回条数（页面只展示前 N 条，逼用户输入更精确的关键字）
     */
    public List<StockListItem> search(String keyword, String market, int limit) {
        Map<String, Object> params = new HashMap<>();
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        String order = " ORDER BY stock_code";
        if (StringUtils.hasText(keyword)) {
            String kw = keyword.trim();
            where.append(" AND (stock_code LIKE :kw OR stock_name LIKE :kw) ");
            params.put("kw", "%" + kw + "%");
            params.put("exact", kw);
            params.put("prefix", kw + "%");
            order = " ORDER BY CASE WHEN stock_code = :exact THEN 0"
                    + " WHEN stock_code LIKE :prefix THEN 1 ELSE 2 END, stock_code";
        }
        if (StringUtils.hasText(market)) {
            where.append(" AND market = :market ");
            params.put("market", market.trim());
        }
        params.put("limit", limit);
        return named.query(
                "SELECT id, stock_code, stock_name, market, enabled FROM " + TABLE
                        + where + order + " LIMIT :limit",
                params, new BeanPropertyRowMapper<>(StockListItem.class));
    }

    public List<StockListItem> findAll(String market) {
        if (StringUtils.hasText(market)) {
            return jdbcTemplate.query(
                    "SELECT id, stock_code, stock_name, market, enabled, create_time, update_time FROM "
                            + TABLE + " WHERE market = ? ORDER BY stock_code",
                    new BeanPropertyRowMapper<>(StockListItem.class), market);
        }
        return jdbcTemplate.query(
                "SELECT id, stock_code, stock_name, market, enabled, create_time, update_time FROM "
                        + TABLE + " ORDER BY stock_code",
                new BeanPropertyRowMapper<>(StockListItem.class));
    }

    public List<StockListItem> findByCodes(List<String> codes, String market) {
        if (codes == null || codes.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Object> params = new HashMap<>();
        params.put("codes", codes);
        params.put("market", StringUtils.hasText(market) ? market : "stock");
        return named.query(
                "SELECT id, stock_code, stock_name, market, enabled, create_time, update_time FROM "
                        + TABLE + " WHERE market = :market AND stock_code IN (:codes) ORDER BY stock_code",
                params, new BeanPropertyRowMapper<>(StockListItem.class));
    }

    public StockListItem findById(Long id) {
        try {
            return jdbcTemplate.queryForObject(
                    "SELECT id, stock_code, stock_name, market, enabled, create_time, update_time FROM "
                            + TABLE + " WHERE id = ?",
                    new BeanPropertyRowMapper<>(StockListItem.class), id);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public long insert(StockListItem item) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("code", item.getStockCode())
                .addValue("name", item.getStockName())
                .addValue("market", defaultMarket(item.getMarket()))
                .addValue("enabled", item.getEnabled() == null ? 1 : item.getEnabled());
        named.update("INSERT INTO " + TABLE + " (stock_code, stock_name, market, enabled) "
                + "VALUES (:code, :name, :market, :enabled)", ps, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0L : key.longValue();
    }

    public int update(StockListItem item) {
        return jdbcTemplate.update(
                "UPDATE " + TABLE + " SET stock_code = ?, stock_name = ?, market = ?, enabled = ? WHERE id = ?",
                item.getStockCode(), item.getStockName(), defaultMarket(item.getMarket()),
                item.getEnabled() == null ? 1 : item.getEnabled(), item.getId());
    }

    public int delete(Long id) {
        return jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    /** 按筛选条件统计命中的股票只数（用于批量抓取前预览规模） */
    public long countByFilter(String keyword, String market, Integer enabled) {
        Map<String, Object> params = new HashMap<>();
        Long c = named.queryForObject("SELECT COUNT(1) FROM " + TABLE + filterWhere(keyword, market, enabled, params),
                params, Long.class);
        return c == null ? 0 : c;
    }

    /**
     * 按筛选条件解析出股票代码列表。
     *
     * @param limit 最多返回多少只，防止误操作把全表（1w+）拉起跑
     */
    public List<String> findCodesByFilter(String keyword, String market, Integer enabled, int limit) {
        Map<String, Object> params = new HashMap<>();
        String where = filterWhere(keyword, market, enabled, params);
        String sql = "SELECT stock_code FROM " + TABLE + where + " ORDER BY stock_code";
        if (limit > 0) {
            sql += " LIMIT :limit";
            params.put("limit", limit);
        }
        List<String> codes = named.queryForList(sql, params, String.class);
        return codes == null ? new ArrayList<>() : codes;
    }

    private String filterWhere(String keyword, String market, Integer enabled, Map<String, Object> params) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        if (StringUtils.hasText(keyword)) {
            where.append(" AND (stock_code LIKE :kw OR stock_name LIKE :kw) ");
            params.put("kw", "%" + keyword.trim() + "%");
        }
        if (StringUtils.hasText(market)) {
            where.append(" AND market = :market ");
            params.put("market", market.trim());
        }
        if (enabled != null) {
            where.append(" AND enabled = :enabled ");
            params.put("enabled", enabled);
        }
        return where.toString();
    }

    public String defaultMarket(String market) {
        return StringUtils.hasText(market) ? market.trim() : "stock";
    }

    /** 批量 upsert 导入（stock-list.json） */
    public int upsertBatch(List<StockListItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        int affected = 0;
        int batchSize = 500;
        for (int i = 0; i < items.size(); i += batchSize) {
            List<StockListItem> sub = items.subList(i, Math.min(i + batchSize, items.size()));
            List<Object[]> args = new ArrayList<>(sub.size());
            for (StockListItem it : sub) {
                args.add(new Object[]{it.getStockCode(), it.getStockName(), defaultMarket(it.getMarket())});
            }
            int[] res = jdbcTemplate.batchUpdate(
                    "INSERT INTO " + TABLE + " (stock_code, stock_name, market) VALUES (?, ?, ?) "
                            + "ON DUPLICATE KEY UPDATE stock_name = VALUES(stock_name)",
                    args);
            for (int r : res) {
                if (r != java.sql.Statement.EXECUTE_FAILED) {
                    affected++;
                }
            }
        }
        return affected;
    }

    public long count() {
        Long c = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM " + TABLE, Long.class);
        return c == null ? 0 : c;
    }
}
