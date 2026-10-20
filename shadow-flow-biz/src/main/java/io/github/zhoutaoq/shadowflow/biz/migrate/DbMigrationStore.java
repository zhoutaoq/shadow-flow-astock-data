package io.github.zhoutaoq.shadowflow.biz.migrate;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库模式的迁移实现：一条 SQL 搞定「增量 + 冲突覆盖」。
 *
 * <p>核心是 {@code INSERT ... ON DUPLICATE KEY UPDATE}（MySQL 5.7 起可用）：
 * <ul>
 *   <li>目标表没有这行 → 插入，影响行数 <b>1</b>；</li>
 *   <li>主键或唯一键撞上 → 更新，影响行数 <b>2</b>（内容完全没变时是 <b>0</b>）。</li>
 * </ul>
 * 这三档正好对应「新增 / 覆盖 / 没变」，不需要先查一遍再决定插还是改 ——
 * 日K几十万行时那一遍查询就是几倍的时间。
 *
 * <p><b>行格式</b>：键名 = 数据库列名。导出时按列名逐个写（{@code stock_code} 而不是
 * 实体里的 {@code code}），导入时按列名取值拼参数；日期与时间统一以
 * {@code yyyy-MM-dd} / {@code yyyy-MM-dd HH:mm:ss} 字符串传，MySQL 自己会转。
 *
 * <p><b>回测是唯一的复合维度</b>：主记录 + 每日快照 + 成交明细三张表，包里表现为
 * 一行主记录带 {@code daily} / {@code trades} 两个数组。导入时先删掉该回测已有的子行
 * 再整段写入 —— 子表没有业务唯一键，纯 insert 会在重复导入时翻倍。
 */
@Service
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbMigrationStore implements MigrationStore {

    private static final Logger log = LoggerFactory.getLogger(DbMigrationStore.class);

    /** 批量写入的分片大小：兼顾往返次数与内存占用 */
    private static final int BATCH = 500;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public DbMigrationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String mode() {
        return "db";
    }

    @Override
    public boolean available(MigrateDimension dim) {
        // 数据库模式所有维度都有落点
        return true;
    }

    @Override
    public long count(MigrateDimension dim) {
        TableSpec spec = spec(dim);
        if (spec == null) {
            return 0;
        }
        try {
            if (dim == MigrateDimension.KLINE) {
                // 日K表可能有上千万行，精确 COUNT 要扫整棵索引；用 information_schema 的估算值，
                // 页面上标注「约」—— 它只用来让人心里有数，不参与任何判断
                Long n = jdbc.queryForObject("SELECT TABLE_ROWS FROM information_schema.tables"
                        + " WHERE table_schema = DATABASE() AND table_name = ?", Long.class, spec.table);
                return n == null ? 0 : n;
            }
            Long n = jdbc.queryForObject("SELECT COUNT(*) FROM " + spec.table, Long.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            log.warn("[migrate] 统计 {} 行数失败：{}", dim.getKey(), e.getMessage());
            return 0;
        }
    }

    /* ==================== 导出 ==================== */

    @Override
    public void exportTo(MigrateDimension dim, JsonGenerator gen) throws IOException {
        if (dim == MigrateDimension.BACKTEST) {
            exportBacktest(gen);
            return;
        }
        TableSpec spec = spec(dim);
        if (spec == null) {
            return;
        }
        String sql = "SELECT " + String.join(", ", spec.columns) + " FROM " + spec.table
                + (spec.orderBy == null ? "" : " ORDER BY " + spec.orderBy);
        // fetchSize = Integer.MIN_VALUE：MySQL 驱动的流式游标，否则会把整个结果集先拉进内存
        jdbc.query((PreparedStatementCreator) con -> {
            PreparedStatement ps = con.prepareStatement(sql);
            ps.setFetchSize(Integer.MIN_VALUE);
            return ps;
        }, (RowCallbackHandler) rs -> {
            try {
                gen.writeStartObject();
                for (int i = 0; i < spec.columns.size(); i++) {
                    writeValue(gen, spec.columns.get(i), rs.getObject(i + 1));
                }
                gen.writeEndObject();
            } catch (IOException e) {
                throw new IllegalStateException("导出 " + dim.getKey() + " 失败：" + e.getMessage(), e);
            }
        });
    }

    /** 回测：主记录带两个子数组，一行 = 一次完整回测 */
    private void exportBacktest(JsonGenerator gen) throws IOException {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + String.join(", ", BACKTEST_COLUMNS)
                + " FROM shadowflow_backtest ORDER BY id");
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            gen.writeStartObject();
            for (String c : BACKTEST_COLUMNS) {
                writeValue(gen, c, row.get(c));
            }
            gen.writeArrayFieldStart("daily");
            for (Map<String, Object> d : jdbc.queryForList(
                    "SELECT " + String.join(", ", BACKTEST_DAILY_COLUMNS)
                            + " FROM shadowflow_backtest_daily WHERE backtest_id = ? ORDER BY trade_date", id)) {
                gen.writeStartObject();
                for (String c : BACKTEST_DAILY_COLUMNS) {
                    writeValue(gen, c, d.get(c));
                }
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeArrayFieldStart("trades");
            for (Map<String, Object> t : jdbc.queryForList(
                    "SELECT " + String.join(", ", BACKTEST_TRADE_COLUMNS)
                            + " FROM shadowflow_backtest_trade WHERE backtest_id = ? ORDER BY trade_date, id", id)) {
                gen.writeStartObject();
                for (String c : BACKTEST_TRADE_COLUMNS) {
                    writeValue(gen, c, t.get(c));
                }
                gen.writeEndObject();
            }
            gen.writeEndArray();
            gen.writeEndObject();
        }
    }

    /* ==================== 导入 ==================== */

    @Override
    public ImportStat importFrom(MigrateDimension dim, JsonParser parser) throws IOException {
        if (dim == MigrateDimension.BACKTEST) {
            return importBacktest(parser);
        }
        TableSpec spec = spec(dim);
        if (spec == null) {
            return ImportStat.of(0, 0, 0, 0);
        }
        String sql = upsertSql(spec);
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        List<Object[]> batch = new ArrayList<>(BATCH);

        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException(spec.table + " 数据格式不对：应当是一个数组");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode node = mapper.readTree(parser);
            Object[] args = new Object[spec.columns.size()];
            for (int i = 0; i < spec.columns.size(); i++) {
                args[i] = jdbcValue(spec.columns.get(i), node.get(spec.columns.get(i)));
            }
            batch.add(args);
            rows++;
            if (batch.size() >= BATCH) {
                int[] r = flush(sql, batch);
                inserted += countOf(r, 1);
                updated += countOf(r, 2);
                unchanged += countOf(r, 0);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            int[] r = flush(sql, batch);
            inserted += countOf(r, 1);
            updated += countOf(r, 2);
            unchanged += countOf(r, 0);
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /** 回测：主记录 upsert + 子表整段替换 */
    private ImportStat importBacktest(JsonParser parser) throws IOException {
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        List<Object[]> mains = new ArrayList<>();
        List<Long> mainIds = new ArrayList<>();
        List<List<Object[]>> dailies = new ArrayList<>();
        List<List<Object[]>> trades = new ArrayList<>();

        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("回测数据格式不对：应当是一个数组");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode node = mapper.readTree(parser);
            Object[] args = new Object[BACKTEST_COLUMNS.size()];
            for (int i = 0; i < BACKTEST_COLUMNS.size(); i++) {
                args[i] = jdbcValue(BACKTEST_COLUMNS.get(i), node.get(BACKTEST_COLUMNS.get(i)));
            }
            mains.add(args);
            long id = node.path("id").asLong(0);
            mainIds.add(id);
            dailies.add(childRows(node.get("daily"), BACKTEST_DAILY_COLUMNS));
            trades.add(childRows(node.get("trades"), BACKTEST_TRADE_COLUMNS));
            rows++;
        }

        List<Object[]> mainBatch = new ArrayList<>();
        for (int i = 0; i < mains.size(); i++) {
            mainBatch.add(mains.get(i));
            if (mainBatch.size() >= BATCH || i == mains.size() - 1) {
                int[] r = flush(upsertSql(new TableSpec("shadowflow_backtest", BACKTEST_COLUMNS, "id")), mainBatch);
                inserted += countOf(r, 1);
                updated += countOf(r, 2);
                unchanged += countOf(r, 0);
                mainBatch.clear();
            }
        }
        // 子表：先删后插。子行只属于某一次回测，整体替换才不会被重复导入翻倍
        for (int i = 0; i < mains.size(); i++) {
            Long id = mainIds.get(i);
            if (id <= 0) {
                continue;
            }
            jdbc.update("DELETE FROM shadowflow_backtest_daily WHERE backtest_id = ?", id);
            jdbc.update("DELETE FROM shadowflow_backtest_trade WHERE backtest_id = ?", id);
            insertChildren("shadowflow_backtest_daily", BACKTEST_DAILY_COLUMNS, id, dailies.get(i));
            insertChildren("shadowflow_backtest_trade", BACKTEST_TRADE_COLUMNS, id, trades.get(i));
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /**
     * 子行转参数。{@code id} 一律丢掉（不写进列，让目标库自己发号）：
     * 子表的主键只在本库内唯一，跨环境保留旧 id 只会跟别的回测子行撞车。
     */
    private List<Object[]> childRows(JsonNode arr, List<String> columns) {
        List<Object[]> out = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            Object[] args = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                args[i] = jdbcValue(columns.get(i), n.get(columns.get(i)));
            }
            out.add(args);
        }
        return out;
    }

    private void insertChildren(String table, List<String> columns, long parentId, List<Object[]> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<String> cols = new ArrayList<>();
        List<Integer> used = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if ("id".equals(columns.get(i))) {
                continue;
            }
            cols.add(columns.get(i));
            used.add(i);
        }
        String sql = "INSERT INTO " + table + " (" + String.join(", ", cols) + ") VALUES ("
                + placeholders(cols.size()) + ")";
        for (int from = 0; from < rows.size(); from += BATCH) {
            List<Object[]> chunk = rows.subList(from, Math.min(from + BATCH, rows.size()));
            List<Object[]> args = new ArrayList<>(chunk.size());
            for (Object[] row : chunk) {
                Object[] one = new Object[used.size()];
                for (int i = 0; i < used.size(); i++) {
                    Object v = row[used.get(i)];
                    one[i] = "backtest_id".equals(cols.get(i)) && v == null ? parentId : v;
                }
                args.add(one);
            }
            jdbc.batchUpdate(sql, args);
        }
    }

    private int[] flush(String sql, List<Object[]> rows) {
        int[] r = jdbc.batchUpdate(sql, rows);
        return r == null ? new int[0] : r;
    }

    /** 影响行数统计：1=新增，2=覆盖，0=没变 */
    private static long countOf(int[] results, int kind) {
        long n = 0;
        for (int v : results) {
            // MySQL 批量里也可能返回更大值（多行 ON DUP），按「非 1 即覆盖」归并
            int k = v == 1 ? 1 : (v == 0 ? 0 : 2);
            if (k == kind) {
                n++;
            }
        }
        return n;
    }

    @Override
    public void reloadAfterImport() {
        // 数据库模式的仓储每次都现查，没有内存快照要刷新
    }

    /* ==================== 列定义与取值 ==================== */

    /** 建表语句里的列顺序，与 schema.sql 一致；导入导出都按它走 */
    private static final List<String> USER_COLUMNS = Arrays.asList(
            "id", "username", "password_hash", "password_salt", "github_id", "github_login",
            "role", "enabled", "create_time", "update_time", "last_login_time");

    private static final List<String> SYS_CONFIG_COLUMNS = Arrays.asList(
            "id", "owner_id", "config_scene", "config_key", "config_value", "remark",
            "create_time", "update_time");

    private static final List<String> STOCK_LIST_COLUMNS = Arrays.asList(
            "id", "stock_code", "stock_name", "market", "enabled", "create_time", "update_time");

    private static final List<String> KLINE_COLUMNS = Arrays.asList(
            "stock_code", "market", "trade_date", "open_price", "close_price", "high_price",
            "low_price", "volume", "amount", "pre_close", "create_time", "update_time");

    private static final List<String> ACCOUNT_COLUMNS = Arrays.asList(
            "id", "user_id", "account_name", "init_value", "cash", "day_realized_date",
            "day_realized_profit", "remark", "create_time", "update_time");

    private static final List<String> POSITION_COLUMNS = Arrays.asList(
            "id", "user_id", "account_id", "stock_code", "stock_name", "cost_price", "quantity",
            "available_qty", "open_date", "day_buy_date", "day_buy_qty", "day_buy_amount",
            "create_time", "update_time");

    private static final List<String> TRADE_RECORD_COLUMNS = Arrays.asList(
            "id", "user_id", "account_id", "action", "stock_code", "stock_name", "price",
            "quantity", "turnover", "cash_after", "same_day", "realized_profit", "trade_date",
            "record_time", "create_time");

    private static final List<String> TREND_COLUMNS = Arrays.asList(
            "id", "user_id", "account_id", "trade_date", "minute", "total_value",
            "position_value", "day_profit", "create_time");

    private static final List<String> BACKTEST_COLUMNS = Arrays.asList(
            "id", "user_id", "stock_code", "market", "start_date", "end_date", "init_amount",
            "fee_rate", "buy_rule", "sell_rule", "final_amount", "total_profit", "profit_ratio",
            "trade_count", "trade_days", "create_time");

    private static final List<String> BACKTEST_DAILY_COLUMNS = Arrays.asList(
            "id", "backtest_id", "trade_date", "close_price", "cash", "position_shares",
            "position_value", "total_amount", "profit_amount", "profit_ratio");

    private static final List<String> BACKTEST_TRADE_COLUMNS = Arrays.asList(
            "id", "backtest_id", "trade_date", "action", "shares", "price", "turnover", "fee",
            "cash_after");

    /** 每个维度落到哪张表、哪些列 */
    private static TableSpec spec(MigrateDimension dim) {
        switch (dim) {
            case USERS:
                return new TableSpec("shadowflow_user", USER_COLUMNS, "id");
            case SYS_CONFIG:
                return new TableSpec("shadowflow_sys_config", SYS_CONFIG_COLUMNS, "id");
            case STOCK_LIST:
                return new TableSpec("shadowflow_stock_list", STOCK_LIST_COLUMNS, "id");
            case KLINE:
                return new TableSpec("shadowflow_stock_kline_daily", KLINE_COLUMNS, "stock_code, trade_date");
            case ACCOUNT:
                return new TableSpec("shadowflow_account", ACCOUNT_COLUMNS, "id");
            case POSITION:
                return new TableSpec("shadowflow_position", POSITION_COLUMNS, "id");
            case TRADE_RECORD:
                return new TableSpec("shadowflow_trade_record", TRADE_RECORD_COLUMNS, "id");
            case ACCOUNT_TREND:
                return new TableSpec("shadowflow_account_trend", TREND_COLUMNS, "id");
            case BACKTEST:
                return new TableSpec("shadowflow_backtest", BACKTEST_COLUMNS, "id");
            default:
                return null;
        }
    }

    /** 冲突时更新除主键 id 以外的所有列：id 是自增主键，改它等于换行 */
    private static String upsertSql(TableSpec spec) {
        List<String> updates = new ArrayList<>();
        for (String c : spec.columns) {
            if ("id".equals(c)) {
                continue;
            }
            updates.add(c + " = VALUES(" + c + ")");
        }
        return "INSERT INTO " + spec.table + " (" + String.join(", ", spec.columns) + ") VALUES ("
                + placeholders(spec.columns.size()) + ") ON DUPLICATE KEY UPDATE "
                + String.join(", ", updates);
    }

    private static String placeholders(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "?" : ", ?");
        }
        return sb.toString();
    }

    /**
     * JSON 值 → JDBC 参数。
     *
     * <p>布尔统一转 1/0：库里 {@code enabled} / {@code same_day} 都是 TINYINT，
     * 直接塞 {@code true} 在部分驱动版本上会被当成字符串 "true" 写进数值列。
     */
    /**
     * 带列名的取值：{@code create_time} / {@code update_time} 在库里是 NOT NULL，
     * 而文件模式的名录种子行这两列可能是 null —— 直接塞 null 会撞「Column cannot be null」，
     * 这里按导入时刻补一个值。
     */
    private static Object jdbcValue(String column, JsonNode n) {
        Object v = jdbcValue(n);
        if (v == null && ("create_time".equals(column) || "update_time".equals(column))) {
            return java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return v;
    }

    private static Object jdbcValue(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean() ? 1 : 0;
        }
        if (n.isIntegralNumber()) {
            return n.asLong();
        }
        if (n.isNumber()) {
            return n.decimalValue();
        }
        String s = n.asText();
        return s.isEmpty() ? null : s;
    }

    /** 结果集值 → JSON。日期 / 时间转字符串，其余按类型写，别把数字写成字符串 */
    private static void writeValue(JsonGenerator gen, String field, Object v) throws IOException {
        if (v == null) {
            gen.writeNullField(field);
            return;
        }
        if (v instanceof BigDecimal) {
            gen.writeNumberField(field, (BigDecimal) v);
        } else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            gen.writeNumberField(field, ((Number) v).longValue());
        } else if (v instanceof Boolean) {
            gen.writeBooleanField(field, (Boolean) v);
        } else if (v instanceof java.sql.Date || v instanceof java.sql.Timestamp
                || v instanceof java.sql.Time) {
            gen.writeStringField(field, v.toString());
        } else if (v instanceof Number) {
            // 兜底：其它数值类型按「原样数字」写，别退化成带引号的字符串
            gen.writeFieldName(field);
            gen.writeNumber(v.toString());
        } else {
            gen.writeStringField(field, v.toString());
        }
    }

    /** 一张表的物理描述 */
    private static final class TableSpec {
        final String table;
        final List<String> columns;
        /** 仅用于导出排序，保证同一份数据每次导出的顺序一致（便于 diff） */
        final String orderBy;

        TableSpec(String table, List<String> columns, String orderBy) {
            this.table = table;
            this.columns = Collections.unmodifiableList(columns);
            this.orderBy = orderBy;
        }
    }
}
