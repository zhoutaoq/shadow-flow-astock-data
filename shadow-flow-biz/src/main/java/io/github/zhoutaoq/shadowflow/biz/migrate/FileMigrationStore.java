package io.github.zhoutaoq.shadowflow.biz.migrate;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import io.github.zhoutaoq.shadowflow.biz.store.JsonFileIo;
import io.github.zhoutaoq.shadowflow.biz.store.Reloadable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件模式的迁移实现：直接读写 {@code ./data} 下的 JSON 文件。
 *
 * <p>与数据库实现共用同一套「行格式」（键名 = 数据库列名），差别只在落点：
 * 数据库那边是一句 {@code INSERT ... ON DUPLICATE KEY UPDATE}，这边是
 * 「整份读进内存 → 按主键 / 唯一键合并 → 原子写回」。
 *
 * <p><b>三种文件结构要分别对待</b>，这是本类绝大部分代码的来源：
 * <ul>
 *   <li><b>整表一个文件</b>：{@code users.json} / {@code sys-config.json} /
 *       {@code stock-list.json} —— 一行一个对象；</li>
 *   <li><b>一个用户一个文件</b>：{@code account/account-{uid}.json} 是
 *       「账户 + 持仓 + 流水」的三层树，{@code account/trend-{uid}.json} 是
 *       「账户 → 日期 → 分钟点」；导入时要按归属拆回各自的文件；</li>
 *   <li><b>一次回测一个文件</b>：{@code backtest/bt-{id}.json}，包里的「一行」=
 *       主记录 + 每日快照 + 成交明细。</li>
 * </ul>
 *
 * <p><b>导入完成后必须重载内存</b>：文件模式的仓储启动时就把数据载进内存，绕过它们改文件
 * 会出现「文件新、页面旧」，下一次业务写入还会用旧快照把导入的数据盖掉。
 * 所以 {@link #reloadAfterImport()} 会把所有实现了 {@link Reloadable} 的仓储挨个刷一遍。
 */
@Service
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileMigrationStore implements MigrationStore {

    private static final Logger log = LoggerFactory.getLogger(FileMigrationStore.class);

    private static final String USERS_FILE = "users.json";
    private static final String SYS_CONFIG_FILE = "sys-config.json";
    private static final String STOCK_LIST_FILE = "stock-list.json";
    private static final String ACCOUNT_DIR = "account";
    private static final String ACCOUNT_PREFIX = "account-";
    private static final String TREND_PREFIX = "trend-";
    private static final String BACKTEST_DIR = "backtest";

    private final DataDir dataDir;
    private final List<Reloadable> reloadables;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileMigrationStore(DataDir dataDir, List<Reloadable> reloadables) {
        this.dataDir = dataDir;
        this.reloadables = reloadables;
    }

    @Override
    public String mode() {
        return "file";
    }

    @Override
    public boolean available(MigrateDimension dim) {
        // 文件模式不落 K 线：K 线按需向行情服务现取，没有存量可搬
        return dim != MigrateDimension.KLINE;
    }

    @Override
    public long count(MigrateDimension dim) {
        try {
            switch (dim) {
                case USERS:
                    return JsonFileIo.readList(dataDir.file(USERS_FILE), Object.class).size();
                case SYS_CONFIG:
                    return JsonFileIo.readList(dataDir.file(SYS_CONFIG_FILE), Object.class).size();
                case STOCK_LIST:
                    return JsonFileIo.readList(dataDir.file(STOCK_LIST_FILE), Object.class).size();
                case ACCOUNT:
                case POSITION:
                case TRADE_RECORD:
                    return countAccountRows(dim);
                case ACCOUNT_TREND:
                    return countTrendRows();
                case BACKTEST:
                    return files(dataDir.root().resolve(BACKTEST_DIR), "bt-*.json").size();
                default:
                    return 0;
            }
        } catch (Exception e) {
            log.warn("[migrate] 统计 {} 行数失败：{}", dim.getKey(), e.getMessage());
            return 0;
        }
    }

    /* ==================== 导出 ==================== */

    @Override
    public void exportTo(MigrateDimension dim, JsonGenerator gen) throws IOException {
        switch (dim) {
            case USERS:
                exportUsers(gen);
                break;
            case SYS_CONFIG:
                exportSysConfig(gen);
                break;
            case STOCK_LIST:
                exportStockList(gen);
                break;
            case ACCOUNT:
                exportAccount(gen, "accounts");
                break;
            case POSITION:
                exportAccount(gen, "positions");
                break;
            case TRADE_RECORD:
                exportAccount(gen, "records");
                break;
            case ACCOUNT_TREND:
                exportTrend(gen);
                break;
            case BACKTEST:
                exportBacktest(gen);
                break;
            default:
                // K 线在文件模式没有存量，写一个空数组，包结构保持一致
                break;
        }
    }

    private void exportUsers(JsonGenerator gen) throws IOException {
        for (JsonNode u : readArray(dataDir.file(USERS_FILE))) {
            ObjectNode n = mapper.createObjectNode();
            putId(n, u.path("id"));
            n.put("username", text(u, "username"));
            n.put("password_hash", text(u, "passwordHash"));
            n.put("password_salt", text(u, "passwordSalt"));
            n.put("github_id", text(u, "githubId"));
            n.put("github_login", text(u, "githubLogin"));
            n.put("role", text(u, "role"));
            n.put("enabled", u.path("enabled").asBoolean(true) ? 1 : 0);
            n.put("create_time", text(u, "createTime"));
            n.put("update_time", text(u, "updateTime"));
            n.put("last_login_time", text(u, "lastLoginTime"));
            mapper.writeTree(gen, n);
        }
    }

    private void exportSysConfig(JsonGenerator gen) throws IOException {
        for (JsonNode c : readArray(dataDir.file(SYS_CONFIG_FILE))) {
            ObjectNode n = mapper.createObjectNode();
            putId(n, c.path("id"));
            n.put("owner_id", c.path("ownerId").asLong(0));
            n.put("config_scene", text(c, "configScene"));
            n.put("config_key", text(c, "configKey"));
            n.put("config_value", text(c, "configValue"));
            n.put("remark", text(c, "remark"));
            n.put("create_time", text(c, "createTime"));
            n.put("update_time", text(c, "updateTime"));
            mapper.writeTree(gen, n);
        }
    }

    private void exportStockList(JsonGenerator gen) throws IOException {
        for (JsonNode s : readArray(dataDir.file(STOCK_LIST_FILE))) {
            ObjectNode n = mapper.createObjectNode();
            putId(n, s.path("id"));
            n.put("stock_code", text(s, "stockCode"));
            n.put("stock_name", text(s, "stockName"));
            n.put("market", text(s, "market"));
            n.put("enabled", s.path("enabled").asInt(1));
            n.put("create_time", text(s, "createTime"));
            n.put("update_time", text(s, "updateTime"));
            mapper.writeTree(gen, n);
        }
    }

    /** 账户 / 持仓 / 流水：三者同在一个用户文件里，按 section 取不同的段 */
    private void exportAccount(JsonGenerator gen, String section) throws IOException {
        for (Path p : files(dataDir.root().resolve(ACCOUNT_DIR), ACCOUNT_PREFIX + "*.json")) {
            long uid = trailingId(p.getFileName().toString(), ACCOUNT_PREFIX);
            ObjectNode root = readObject(p);
            if (root == null || uid <= 0) {
                continue;
            }
            if ("accounts".equals(section)) {
                JsonNode arr = root.path("accounts");
                if (!arr.isArray() && root.path("account").isObject()) {
                    // v1.5.1 老结构：单本账直接摊在 account 字段上
                    ObjectNode n = accountRow(root.path("account"), uid);
                    n.put("id", 1L);
                    mapper.writeTree(gen, n);
                    continue;
                }
                for (JsonNode a : arr) {
                    mapper.writeTree(gen, accountRow(a, uid));
                }
                continue;
            }
            String field = "positions".equals(section) ? "positions" : "records";
            JsonNode groups = root.path(field);
            if (!groups.isObject()) {
                continue;
            }
            for (Map.Entry<String, JsonNode> e : iterable(groups.fields())) {
                long accountId = parseLong(e.getKey());
                if (!e.getValue().isArray()) {
                    continue;
                }
                for (JsonNode row : e.getValue()) {
                    ObjectNode n = "positions".equals(section)
                            ? positionRow(row, uid, accountId)
                            : tradeRow(row, uid, accountId);
                    mapper.writeTree(gen, n);
                }
            }
        }
    }

    private ObjectNode accountRow(JsonNode a, long uid) {
        ObjectNode n = mapper.createObjectNode();
        putId(n, a.path("id"));
        n.put("user_id", a.path("userId").asLong(uid));
        n.put("account_name", text(a, "name"));
        n.put("init_value", decimal(a, "initValue"));
        n.put("cash", decimal(a, "cash"));
        n.put("day_realized_date", text(a, "dayRealizedDate"));
        n.put("day_realized_profit", decimal(a, "dayRealizedProfit"));
        n.put("remark", text(a, "remark"));
        n.put("create_time", text(a, "createTime"));
        n.put("update_time", text(a, "updateTime"));
        return n;
    }

    private ObjectNode positionRow(JsonNode p, long uid, long accountId) {
        ObjectNode n = mapper.createObjectNode();
        putId(n, p.path("id"));
        n.put("user_id", p.path("userId").asLong(uid));
        n.put("account_id", p.path("accountId").asLong(accountId));
        n.put("stock_code", text(p, "code"));
        n.put("stock_name", text(p, "name"));
        n.put("cost_price", decimal(p, "costPrice"));
        n.put("quantity", p.path("quantity").asInt(0));
        if (p.hasNonNull("availableQty")) {
            n.put("available_qty", p.path("availableQty").asInt());
        } else {
            n.putNull("available_qty");
        }
        n.put("open_date", text(p, "openDate"));
        n.put("day_buy_date", text(p, "dayBuyDate"));
        n.put("day_buy_qty", p.path("dayBuyQty").asInt(0));
        n.put("day_buy_amount", decimal(p, "dayBuyAmount"));
        n.put("create_time", text(p, "createTime"));
        n.put("update_time", text(p, "updateTime"));
        return n;
    }

    private ObjectNode tradeRow(JsonNode r, long uid, long accountId) {
        ObjectNode n = mapper.createObjectNode();
        putId(n, r.path("id"));
        n.put("user_id", r.path("userId").asLong(uid));
        n.put("account_id", r.path("accountId").asLong(accountId));
        n.put("action", text(r, "action"));
        n.put("stock_code", text(r, "code"));
        n.put("stock_name", text(r, "name"));
        n.put("price", decimal(r, "price"));
        n.put("quantity", r.path("quantity").asInt(0));
        n.put("turnover", decimal(r, "turnover"));
        n.put("cash_after", decimal(r, "cashAfter"));
        n.put("same_day", r.path("sameDay").asBoolean(true) ? 1 : 0);
        n.put("realized_profit", decimal(r, "realizedProfit"));
        n.put("trade_date", text(r, "tradeDate"));
        n.put("record_time", text(r, "time"));
        n.putNull("create_time");
        return n;
    }

    private void exportTrend(JsonGenerator gen) throws IOException {
        for (Path p : files(dataDir.root().resolve(ACCOUNT_DIR), TREND_PREFIX + "*.json")) {
            long uid = trailingId(p.getFileName().toString(), TREND_PREFIX);
            ObjectNode root = readObject(p);
            if (root == null || uid <= 0) {
                continue;
            }
            for (Map.Entry<String, JsonNode> acc : iterable(root.fields())) {
                long accountId = parseLong(acc.getKey());
                if (accountId <= 0 || !acc.getValue().isObject()) {
                    continue;
                }
                for (Map.Entry<String, JsonNode> day : iterable(acc.getValue().fields())) {
                    if (!day.getValue().isArray()) {
                        continue;
                    }
                    for (JsonNode point : day.getValue()) {
                        ObjectNode n = mapper.createObjectNode();
                        n.putNull("id");
                        n.put("user_id", uid);
                        n.put("account_id", accountId);
                        n.put("trade_date", day.getKey());
                        n.put("minute", text(point, "minute"));
                        n.put("total_value", decimal(point, "totalValue"));
                        n.put("position_value", decimal(point, "positionValue"));
                        n.put("day_profit", decimal(point, "dayProfit"));
                        n.putNull("create_time");
                        mapper.writeTree(gen, n);
                    }
                }
            }
        }
    }

    private void exportBacktest(JsonGenerator gen) throws IOException {
        for (Path p : files(dataDir.root().resolve(BACKTEST_DIR), "bt-*.json")) {
            ObjectNode root = readObject(p);
            if (root == null || !root.path("backtest").isObject()) {
                continue;
            }
            long fileId = trailingId(p.getFileName().toString(), "bt-");
            JsonNode bt = root.path("backtest");
            ObjectNode n = mapper.createObjectNode();
            n.put("id", bt.path("id").isNumber() ? bt.path("id").asLong() : fileId);
            n.put("user_id", bt.path("userId").asLong(0));
            n.put("stock_code", text(bt, "stockCode"));
            n.put("market", text(bt, "market"));
            n.put("start_date", text(bt, "startDate"));
            n.put("end_date", text(bt, "endDate"));
            n.put("init_amount", decimal(bt, "initAmount"));
            n.put("fee_rate", decimal(bt, "feeRate"));
            n.put("buy_rule", text(bt, "buyRule"));
            n.put("sell_rule", text(bt, "sellRule"));
            n.put("final_amount", decimal(bt, "finalAmount"));
            n.put("total_profit", decimal(bt, "totalProfit"));
            n.put("profit_ratio", decimal(bt, "profitRatio"));
            n.put("trade_count", bt.path("tradeCount").asInt(0));
            n.put("trade_days", bt.path("tradeDays").asInt(0));
            n.put("create_time", text(bt, "createTime"));
            ArrayNode daily = n.putArray("daily");
            for (JsonNode d : root.path("dailies")) {
                ObjectNode dn = mapper.createObjectNode();
                putId(dn, d.path("id"));
                dn.put("backtest_id", n.path("id").asLong());
                dn.put("trade_date", text(d, "tradeDate"));
                dn.put("close_price", decimal(d, "closePrice"));
                dn.put("cash", decimal(d, "cash"));
                dn.put("position_shares", d.path("positionShares").asInt(0));
                dn.put("position_value", decimal(d, "positionValue"));
                dn.put("total_amount", decimal(d, "totalAmount"));
                dn.put("profit_amount", decimal(d, "profitAmount"));
                dn.put("profit_ratio", decimal(d, "profitRatio"));
                daily.add(dn);
            }
            ArrayNode trade = n.putArray("trades");
            for (JsonNode t : root.path("trades")) {
                ObjectNode tn = mapper.createObjectNode();
                putId(tn, t.path("id"));
                tn.put("backtest_id", n.path("id").asLong());
                tn.put("trade_date", text(t, "tradeDate"));
                tn.put("action", text(t, "action"));
                tn.put("shares", t.path("shares").asInt(0));
                tn.put("price", decimal(t, "price"));
                tn.put("turnover", decimal(t, "turnover"));
                tn.put("fee", decimal(t, "fee"));
                tn.put("cash_after", decimal(t, "cashAfter"));
                trade.add(tn);
            }
            mapper.writeTree(gen, n);
        }
    }

    /* ==================== 导入 ==================== */

    @Override
    public ImportStat importFrom(MigrateDimension dim, JsonParser parser) throws IOException {
        switch (dim) {
            case USERS:
                return importFlat(parser, USERS_FILE,
                        row -> text(row, "username").trim().toLowerCase(),
                        row -> text(row, "username").trim().toLowerCase(),
                        this::userFromRow);
            case SYS_CONFIG:
                return importFlat(parser, SYS_CONFIG_FILE,
                        row -> row.path("owner_id").asLong(0) + "|" + text(row, "config_scene")
                                + "|" + text(row, "config_key"),
                        row -> row.path("ownerId").asLong(0) + "|" + text(row, "configScene")
                                + "|" + text(row, "configKey"),
                        this::sysConfigFromRow);
            case STOCK_LIST:
                // 文件模式里 (代码, 市场) 并不唯一：指数与个股同码（000001 既是平安银行也是上证指数），
                // 只用代码 + 市场当键会把其中一批行覆盖掉，键里必须带上名称
                return importFlat(parser, STOCK_LIST_FILE,
                        row -> text(row, "stock_code") + "|" + text(row, "market")
                                + "|" + text(row, "stock_name"),
                        row -> text(row, "stockCode") + "|" + text(row, "market")
                                + "|" + text(row, "stockName"),
                        this::stockFromRow);
            case ACCOUNT:
                return importAccount(parser);
            case POSITION:
                return importPosition(parser);
            case TRADE_RECORD:
                return importTrade(parser);
            case ACCOUNT_TREND:
                return importTrend(parser);
            case BACKTEST:
                return importBacktest(parser);
            default:
                return ImportStat.of(0, 0, 0, 0);
        }
    }

    /**
     * 整表一个文件的通用合并：按主键 id 覆盖，{@code keyField} 非空时再按业务唯一字段覆盖。
     *
     * <p>两种冲突的处理不一样，因为后果不一样：
     * <ul>
     *   <li><b>业务唯一键撞上</b>（用户名、owner+scene+key、代码+市场）—— 更新那一行、
     *       保留目标环境原来的 id：id 可能被别的数据引用着，换掉等于把引用断开；</li>
     *   <li><b>只有 id 撞上、业务键不同</b> —— 说明目标环境里这个 id 是另一条数据，
     *       直接覆盖就是误伤，改为分配一个新 id 追加。</li>
     * </ul>
     */
    private ImportStat importFlat(JsonParser parser, String file, KeyFn keyFn, KeyFn fileKeyFn,
                                  java.util.function.Function<JsonNode, ObjectNode> toFileNode)
            throws IOException {
        Path path = dataDir.file(file);
        List<JsonNode> list = new ArrayList<>(readArray(path));
        Map<String, Integer> byKey = new LinkedHashMap<>();
        Map<Long, Integer> byId = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            // 存量文件里的对象是驼峰字段（stockCode），包里的行是列名（stock_code），
            // 两边的键要分别算 —— 混用会让「本该覆盖」的行变成「全部新增」
            indexRow(list.get(i), i, byKey, byId, fileKeyFn);
        }
        long maxId = maxId(list);
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;

        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException(file + " 数据格式不对：应当是一个数组");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode row = mapper.readTree(parser);
            rows++;
            String key = keyFn.key(row);
            long id = row.path("id").asLong(0);
            Integer hit = byKey.get(key);
            if (hit == null && id > 0 && byId.containsKey(id)) {
                // 业务键没撞、id 却撞了：目标环境里这个 id 是另一条数据，直接覆盖就是误伤，
                // 改为分配一个新 id 追加（业务键相同的情况在上面已按「覆盖」处理）
                ObjectNode neo = toFileNode.apply(row);
                neo.put("id", ++maxId);
                list.add(neo);
                indexRow(neo, list.size() - 1, byKey, byId, fileKeyFn);
                inserted++;
                continue;
            }
            if (hit != null) {
                JsonNode old = list.get(hit);
                ObjectNode neo = toFileNode.apply(row);
                // 业务键命中：保留目标环境原来的 id（可能被别处引用着）
                neo.put("id", old.path("id").asLong(0));
                if (same(old, neo)) {
                    unchanged++;
                } else {
                    list.set(hit, neo);
                    indexRow(neo, hit, byKey, byId, fileKeyFn);
                    updated++;
                }
            } else {
                ObjectNode neo = toFileNode.apply(row);
                long newId = neo.path("id").asLong(0);
                if (newId <= 0) {
                    neo.put("id", ++maxId);
                } else {
                    maxId = Math.max(maxId, newId);
                }
                list.add(neo);
                indexRow(neo, list.size() - 1, byKey, byId, fileKeyFn);
                inserted++;
            }
        }
        JsonFileIo.write(path, list);
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /** 账户：按 user_id 拆回各自的 account-{uid}.json，账户按 id 合并 */
    private ImportStat importAccount(JsonParser parser) throws IOException {
        Map<Long, List<JsonNode>> byUser = readGrouped(parser);
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        for (Map.Entry<Long, List<JsonNode>> e : byUser.entrySet()) {
            long uid = e.getKey();
            if (uid <= 0) {
                log.warn("[migrate] 跳过 {} 条没有 user_id 的账户", e.getValue().size());
                continue;
            }
            ObjectNode root = accountRoot(uid);
            ArrayNode accounts = root.withArray("accounts");
            Map<Long, Integer> byId = new HashMap<>();
            for (int i = 0; i < accounts.size(); i++) {
                byId.put(accounts.get(i).path("id").asLong(0), i);
            }
            long maxId = maxId(accounts);
            for (JsonNode row : e.getValue()) {
                rows++;
                ObjectNode neo = accountNode(row);
                long id = neo.path("id").asLong(0);
                Integer hit = id > 0 ? byId.get(id) : null;
                if (hit != null) {
                    if (same(accounts.get(hit), neo)) {
                        unchanged++;
                    } else {
                        accounts.set(hit, neo);
                        updated++;
                    }
                } else {
                    if (id <= 0) {
                        neo.put("id", ++maxId);
                        byId.put(maxId, accounts.size());
                    } else {
                        maxId = Math.max(maxId, id);
                        byId.put(id, accounts.size());
                    }
                    accounts.add(neo);
                    inserted++;
                }
            }
            JsonFileIo.write(fileOfAccount(uid), root);
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /** 持仓：按 (user, account) 归组，id 覆盖；同一账户同一代码的旧行先删掉（业务唯一键） */
    private ImportStat importPosition(JsonParser parser) throws IOException {
        Map<Long, Map<Long, List<JsonNode>>> grouped = readGroupedByAccount(parser);
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        for (Map.Entry<Long, Map<Long, List<JsonNode>>> byUser : grouped.entrySet()) {
            long uid = byUser.getKey();
            if (uid <= 0) {
                continue;
            }
            ObjectNode root = accountRoot(uid);
            ObjectNode positions = root.with("positions");
            for (Map.Entry<Long, List<JsonNode>> byAccount : byUser.getValue().entrySet()) {
                long accountId = byAccount.getKey();
                ArrayNode bucket = positions.withArray(String.valueOf(accountId));
                Map<Long, Integer> byId = new HashMap<>();
                for (int i = 0; i < bucket.size(); i++) {
                    byId.put(bucket.get(i).path("id").asLong(0), i);
                }
                long maxId = maxId(bucket);
                for (JsonNode row : byAccount.getValue()) {
                    rows++;
                    ObjectNode neo = positionNode(row);
                    String code = neo.path("stock_code").asText("");
                    // 同一账户同一标的只应有一条：先把「撞代码但 id 不同」的旧行摘掉
                    for (int i = bucket.size() - 1; i >= 0; i--) {
                        if (code.equals(bucket.get(i).path("code").asText())
                                && bucket.get(i).path("id").asLong(0) != neo.path("id").asLong(0)) {
                            bucket.remove(i);
                        }
                    }
                    byId.clear();
                    for (int i = 0; i < bucket.size(); i++) {
                        byId.put(bucket.get(i).path("id").asLong(0), i);
                    }
                    long id = neo.path("id").asLong(0);
                    Integer hit = id > 0 ? byId.get(id) : null;
                    if (hit != null) {
                        if (same(bucket.get(hit), neo)) {
                            unchanged++;
                        } else {
                            bucket.set(hit, neo);
                            updated++;
                        }
                    } else {
                        if (id <= 0) {
                            neo.put("id", ++maxId);
                        } else {
                            maxId = Math.max(maxId, id);
                        }
                        bucket.add(neo);
                        inserted++;
                    }
                }
            }
            JsonFileIo.write(fileOfAccount(uid), root);
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /** 流水：按 (user, account) 归组，id 覆盖；没有业务唯一键，重复导入不会翻倍（id 不变） */
    private ImportStat importTrade(JsonParser parser) throws IOException {
        Map<Long, Map<Long, List<JsonNode>>> grouped = readGroupedByAccount(parser);
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        for (Map.Entry<Long, Map<Long, List<JsonNode>>> byUser : grouped.entrySet()) {
            long uid = byUser.getKey();
            if (uid <= 0) {
                continue;
            }
            ObjectNode root = accountRoot(uid);
            ObjectNode records = root.with("records");
            for (Map.Entry<Long, List<JsonNode>> byAccount : byUser.getValue().entrySet()) {
                long accountId = byAccount.getKey();
                ArrayNode bucket = records.withArray(String.valueOf(accountId));
                Map<Long, Integer> byId = new HashMap<>();
                for (int i = 0; i < bucket.size(); i++) {
                    byId.put(bucket.get(i).path("id").asLong(0), i);
                }
                long maxId = maxId(bucket);
                for (JsonNode row : byAccount.getValue()) {
                    rows++;
                    ObjectNode neo = tradeNode(row);
                    long id = neo.path("id").asLong(0);
                    Integer hit = id > 0 ? byId.get(id) : null;
                    if (hit != null) {
                        if (same(bucket.get(hit), neo)) {
                            unchanged++;
                        } else {
                            bucket.set(hit, neo);
                            updated++;
                        }
                    } else {
                        if (id <= 0) {
                            neo.put("id", ++maxId);
                        } else {
                            maxId = Math.max(maxId, id);
                        }
                        bucket.add(neo);
                        inserted++;
                    }
                }
            }
            JsonFileIo.write(fileOfAccount(uid), root);
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /** 走势：唯一键是 (账户, 日期, 分钟)，同一分钟以导入数据为准 */
    private ImportStat importTrend(JsonParser parser) throws IOException {
        Map<Long, List<JsonNode>> byUser = readGrouped(parser);
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        for (Map.Entry<Long, List<JsonNode>> e : byUser.entrySet()) {
            long uid = e.getKey();
            if (uid <= 0) {
                continue;
            }
            Path path = dataDir.file(ACCOUNT_DIR + "/" + TREND_PREFIX + uid + ".json");
            ObjectNode root = readObject(path);
            if (root == null) {
                root = mapper.createObjectNode();
            }
            for (JsonNode row : e.getValue()) {
                rows++;
                long accountId = row.path("account_id").asLong(0);
                String date = text(row, "trade_date");
                String minute = text(row, "minute");
                if (accountId <= 0 || date.isEmpty() || minute.isEmpty()) {
                    continue;
                }
                ObjectNode byDate = root.with(String.valueOf(accountId));
                ArrayNode points = byDate.withArray(date);
                ObjectNode neo = mapper.createObjectNode();
                neo.put("minute", minute);
                neo.put("totalValue", decimal(row, "total_value"));
                neo.put("positionValue", decimal(row, "position_value"));
                neo.put("dayProfit", decimal(row, "day_profit"));
                int hit = -1;
                for (int i = 0; i < points.size(); i++) {
                    if (minute.equals(points.get(i).path("minute").asText())) {
                        hit = i;
                        break;
                    }
                }
                if (hit >= 0) {
                    if (same(points.get(hit), neo)) {
                        unchanged++;
                    } else {
                        points.set(hit, neo);
                        updated++;
                    }
                } else {
                    points.add(neo);
                    inserted++;
                }
            }
            JsonFileIo.write(path, root);
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    /** 回测：一次回测一个文件，按 id 整份覆盖（子表随主记录一起换，不会翻倍） */
    private ImportStat importBacktest(JsonParser parser) throws IOException {
        Path dir = dataDir.dir(BACKTEST_DIR);
        long maxId = 0;
        for (Path p : files(dir, "bt-*.json")) {
            maxId = Math.max(maxId, trailingId(p.getFileName().toString(), "bt-"));
        }
        long rows = 0, inserted = 0, updated = 0, unchanged = 0;
        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("回测数据格式不对：应当是一个数组");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode row = mapper.readTree(parser);
            rows++;
            long id = row.path("id").asLong(0);
            if (id <= 0) {
                id = ++maxId;
            } else {
                maxId = Math.max(maxId, id);
            }
            ObjectNode root = backtestNode(row, id);
            Path target = dir.resolve("bt-" + id + ".json");
            ObjectNode old = readObject(target);
            if (old != null) {
                if (same(old, root)) {
                    unchanged++;
                } else {
                    JsonFileIo.write(target, root);
                    updated++;
                }
            } else {
                JsonFileIo.write(target, root);
                inserted++;
            }
        }
        return ImportStat.of(rows, inserted, updated, unchanged);
    }

    @Override
    public void reloadAfterImport() {
        for (Reloadable r : reloadables) {
            try {
                r.reloadData();
            } catch (Exception e) {
                log.warn("[migrate] 重载 {} 失败：{}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /* ==================== 行 ⇄ 文件对象 ==================== */

    /** 业务唯一键：从 canonical 行里算出「这一行在目标环境该跟谁合并」 */
    private interface KeyFn {
        String key(JsonNode row);
    }

    private ObjectNode userFromRow(JsonNode r) {
        return mapper.createObjectNode()
                .put("id", r.path("id").asLong(0))
                .put("username", text(r, "username"))
                .put("passwordHash", text(r, "password_hash"))
                .put("passwordSalt", text(r, "password_salt"))
                .put("githubId", nullIfEmpty(text(r, "github_id")))
                .put("githubLogin", nullIfEmpty(text(r, "github_login")))
                .put("role", text(r, "role"))
                // 文件里的 enabled 是布尔，这里跟着布尔写：写 1/0 也能读，
                // 但会让「内容没变」被判成「覆盖」，导入结果里全是没必要的覆盖数
                .put("enabled", r.path("enabled").asInt(1) != 0)
                .put("createTime", nullIfEmpty(text(r, "create_time")))
                .put("updateTime", nullIfEmpty(text(r, "update_time")))
                .put("lastLoginTime", nullIfEmpty(text(r, "last_login_time")));
    }

    private ObjectNode sysConfigFromRow(JsonNode r) {
        return mapper.createObjectNode()
                .put("id", r.path("id").asLong(0))
                .put("ownerId", r.path("owner_id").asLong(0))
                .put("configScene", text(r, "config_scene"))
                .put("configKey", text(r, "config_key"))
                .put("configValue", text(r, "config_value"))
                .put("remark", nullIfEmpty(text(r, "remark")))
                .put("createTime", nullIfEmpty(text(r, "create_time")))
                .put("updateTime", nullIfEmpty(text(r, "update_time")));
    }

    private ObjectNode stockFromRow(JsonNode r) {
        return mapper.createObjectNode()
                .put("id", r.path("id").asLong(0))
                .put("stockCode", text(r, "stock_code"))
                .put("stockName", text(r, "stock_name"))
                .put("market", text(r, "market"))
                .put("enabled", r.path("enabled").asInt(1))
                .put("createTime", nullIfEmpty(text(r, "create_time")))
                .put("updateTime", nullIfEmpty(text(r, "update_time")));
    }

    private ObjectNode accountNode(JsonNode r) {
        return mapper.createObjectNode()
                .put("id", r.path("id").asLong(0))
                .put("userId", r.path("user_id").asLong(0))
                .put("name", text(r, "account_name"))
                .put("initValue", decimal(r, "init_value"))
                .put("cash", decimal(r, "cash"))
                .put("dayRealizedDate", nullIfEmpty(text(r, "day_realized_date")))
                .put("dayRealizedProfit", decimal(r, "day_realized_profit"))
                .put("remark", nullIfEmpty(text(r, "remark")))
                .put("createTime", nullIfEmpty(text(r, "create_time")))
                .put("updateTime", nullIfEmpty(text(r, "update_time")));
    }

    private ObjectNode positionNode(JsonNode r) {
        ObjectNode n = mapper.createObjectNode();
        n.put("id", r.path("id").asLong(0));
        n.put("userId", r.path("user_id").asLong(0));
        n.put("accountId", r.path("account_id").asLong(0));
        n.put("code", text(r, "stock_code"));
        n.put("name", text(r, "stock_name"));
        n.put("costPrice", decimal(r, "cost_price"));
        n.put("quantity", r.path("quantity").asInt(0));
        if (r.hasNonNull("available_qty")) {
            n.put("availableQty", r.path("available_qty").asInt());
        } else {
            n.putNull("availableQty");
        }
        n.put("openDate", nullIfEmpty(text(r, "open_date")));
        n.put("dayBuyDate", nullIfEmpty(text(r, "day_buy_date")));
        n.put("dayBuyQty", r.path("day_buy_qty").asInt(0));
        n.put("dayBuyAmount", decimal(r, "day_buy_amount"));
        n.put("createTime", nullIfEmpty(text(r, "create_time")));
        n.put("updateTime", nullIfEmpty(text(r, "update_time")));
        return n;
    }

    private ObjectNode tradeNode(JsonNode r) {
        return mapper.createObjectNode()
                .put("id", r.path("id").asLong(0))
                .put("userId", r.path("user_id").asLong(0))
                .put("accountId", r.path("account_id").asLong(0))
                .put("action", text(r, "action"))
                .put("code", text(r, "stock_code"))
                .put("name", text(r, "stock_name"))
                .put("price", decimal(r, "price"))
                .put("quantity", r.path("quantity").asInt(0))
                .put("turnover", decimal(r, "turnover"))
                .put("cashAfter", decimal(r, "cash_after"))
                .put("sameDay", r.path("same_day").asInt(1) != 0)
                .put("realizedProfit", decimal(r, "realized_profit"))
                .put("tradeDate", text(r, "trade_date"))
                .put("time", text(r, "record_time"));
    }

    private ObjectNode backtestNode(JsonNode r, long id) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode bt = root.putObject("backtest");
        bt.put("id", id);
        bt.put("userId", r.path("user_id").asLong(0));
        bt.put("stockCode", text(r, "stock_code"));
        bt.put("market", text(r, "market"));
        bt.put("startDate", text(r, "start_date"));
        bt.put("endDate", text(r, "end_date"));
        bt.put("initAmount", decimal(r, "init_amount"));
        bt.put("feeRate", decimal(r, "fee_rate"));
        bt.put("buyRule", text(r, "buy_rule"));
        bt.put("sellRule", text(r, "sell_rule"));
        bt.put("finalAmount", decimal(r, "final_amount"));
        bt.put("totalProfit", decimal(r, "total_profit"));
        bt.put("profitRatio", decimal(r, "profit_ratio"));
        bt.put("tradeCount", r.path("trade_count").asInt(0));
        bt.put("tradeDays", r.path("trade_days").asInt(0));
        bt.put("createTime", nullIfEmpty(text(r, "create_time")));
        ArrayNode dailies = root.putArray("dailies");
        for (JsonNode d : r.path("daily")) {
            dailies.add(mapper.createObjectNode()
                    .put("id", d.path("id").asLong(0))
                    .put("backtestId", id)
                    .put("tradeDate", text(d, "trade_date"))
                    .put("closePrice", decimal(d, "close_price"))
                    .put("cash", decimal(d, "cash"))
                    .put("positionShares", d.path("position_shares").asInt(0))
                    .put("positionValue", decimal(d, "position_value"))
                    .put("totalAmount", decimal(d, "total_amount"))
                    .put("profitAmount", decimal(d, "profit_amount"))
                    .put("profitRatio", decimal(d, "profit_ratio")));
        }
        ArrayNode trades = root.putArray("trades");
        for (JsonNode t : r.path("trades")) {
            trades.add(mapper.createObjectNode()
                    .put("id", t.path("id").asLong(0))
                    .put("backtestId", id)
                    .put("tradeDate", text(t, "trade_date"))
                    .put("action", text(t, "action"))
                    .put("shares", t.path("shares").asInt(0))
                    .put("price", decimal(t, "price"))
                    .put("turnover", decimal(t, "turnover"))
                    .put("fee", decimal(t, "fee"))
                    .put("cashAfter", decimal(t, "cash_after")));
        }
        root.putArray("klines");
        return root;
    }

    /* ==================== 小工具 ==================== */

    /** 读一组「一行一个对象」的数组，按 user_id 分组（账户、走势用） */
    private Map<Long, List<JsonNode>> readGrouped(JsonParser parser) throws IOException {
        Map<Long, List<JsonNode>> out = new LinkedHashMap<>();
        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("数据格式不对：应当是一个数组");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode row = mapper.readTree(parser);
            out.computeIfAbsent(row.path("user_id").asLong(0), k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    /** 读一组「一行一个对象」的数组，按 (user_id, account_id) 分组（持仓、流水用） */
    private Map<Long, Map<Long, List<JsonNode>>> readGroupedByAccount(JsonParser parser) throws IOException {
        Map<Long, Map<Long, List<JsonNode>>> out = new LinkedHashMap<>();
        JsonToken t = parser.nextToken();
        if (t != JsonToken.START_ARRAY) {
            throw new IllegalArgumentException("数据格式不对：应当是一个数组");
        }
        while (parser.nextToken() == JsonToken.START_OBJECT) {
            JsonNode row = mapper.readTree(parser);
            long uid = row.path("user_id").asLong(0);
            long aid = row.path("account_id").asLong(0);
            out.computeIfAbsent(uid, k -> new LinkedHashMap<>())
                    .computeIfAbsent(aid, k -> new ArrayList<>()).add(row);
        }
        return out;
    }

    /** 账户文件（不存在给一份空骨架）；{@code create} 为 false 时不建目录外的空文件 */
    private ObjectNode accountRoot(long uid) {
        Path p = fileOfAccount(uid);
        ObjectNode root = readObject(p);
        if (root == null) {
            root = mapper.createObjectNode();
            root.putArray("accounts");
            root.putObject("positions");
            root.putObject("records");
        }
        if (!root.has("accounts")) {
            root.putArray("accounts");
        }
        if (!root.path("positions").isObject()) {
            root.set("positions", mapper.createObjectNode());
        }
        if (!root.path("records").isObject()) {
            root.set("records", mapper.createObjectNode());
        }
        return root;
    }

    private Path fileOfAccount(long uid) {
        return dataDir.file(ACCOUNT_DIR + "/" + ACCOUNT_PREFIX + uid + ".json");
    }

    private long countAccountRows(MigrateDimension dim) {
        long n = 0;
        for (Path p : files(dataDir.root().resolve(ACCOUNT_DIR), ACCOUNT_PREFIX + "*.json")) {
            ObjectNode root = readObject(p);
            if (root == null) {
                continue;
            }
            if (dim == MigrateDimension.ACCOUNT) {
                n += root.path("accounts").size();
            } else if (dim == MigrateDimension.POSITION) {
                for (Map.Entry<String, JsonNode> e : iterable(root.path("positions").fields())) {
                    n += e.getValue().size();
                }
            } else {
                for (Map.Entry<String, JsonNode> e : iterable(root.path("records").fields())) {
                    n += e.getValue().size();
                }
            }
        }
        return n;
    }

    private long countTrendRows() {
        long n = 0;
        for (Path p : files(dataDir.root().resolve(ACCOUNT_DIR), TREND_PREFIX + "*.json")) {
            ObjectNode root = readObject(p);
            if (root == null) {
                continue;
            }
            for (Map.Entry<String, JsonNode> acc : iterable(root.fields())) {
                for (Map.Entry<String, JsonNode> day : iterable(acc.getValue().fields())) {
                    n += day.getValue().size();
                }
            }
        }
        return n;
    }

    private static void indexRow(JsonNode row, int index, Map<String, Integer> byKey,
                                 Map<Long, Integer> byId, KeyFn keyFn) {
        String key = keyFn == null ? null : keyFn.key(row);
        if (key != null && !key.isEmpty()) {
            byKey.put(key, index);
        }
        long id = row.path("id").asLong(0);
        if (id > 0) {
            byId.put(id, index);
        }
    }

    private static long maxId(List<JsonNode> rows) {
        long max = 0;
        for (JsonNode n : rows) {
            max = Math.max(max, n.path("id").asLong(0));
        }
        return max;
    }

    private static long maxId(ArrayNode rows) {
        long max = 0;
        for (JsonNode n : rows) {
            max = Math.max(max, n.path("id").asLong(0));
        }
        return max;
    }

    private List<JsonNode> readArray(Path p) {
        List<JsonNode> out = new ArrayList<>();
        if (!Files.isRegularFile(p)) {
            return out;
        }
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
            if (root != null && root.isArray()) {
                for (JsonNode n : root) {
                    if (n != null && n.isObject()) {
                        out.add(n);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[migrate] 读取 {} 失败：{}（按空数组处理）", p, e.getMessage());
        }
        return out;
    }

    private ObjectNode readObject(Path p) {
        if (!Files.isRegularFile(p)) {
            return null;
        }
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.UTF_8));
            return root != null && root.isObject() ? (ObjectNode) root : null;
        } catch (Exception e) {
            log.warn("[migrate] 读取 {} 失败：{}（按空结构处理）", p, e.getMessage());
            return null;
        }
    }

    private static List<Path> files(Path dir, String glob) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, glob)) {
            for (Path p : stream) {
                out.add(p);
            }
        } catch (IOException e) {
            log.warn("[migrate] 扫描 {} 失败：{}", dir, e.getMessage());
        }
        out.sort(Comparator.comparing((Path p) -> p.getFileName().toString()));
        return out;
    }

    /** 从 {@code account-12.json} 里取出 12；取不到给 0 */
    private static long trailingId(String fileName, String prefix) {
        if (fileName == null || !fileName.startsWith(prefix) || !fileName.endsWith(".json")) {
            return 0;
        }
        String mid = fileName.substring(prefix.length(), fileName.length() - ".json".length());
        return parseLong(mid);
    }

    /** id 可能为空（走势点、子表的旧数据），空就写 null 而不是 0 —— 0 会被当成「主键 0」 */
    private static void putId(ObjectNode n, JsonNode id) {
        if (id != null && id.isNumber()) {
            n.put("id", id.asLong());
        } else {
            n.putNull("id");
        }
    }


    /**
     * 两个 JSON 节点「内容是否一致」。
     *
     * <p>不能直接用 {@code JsonNode.equals}：Jackson 的数值节点是<b>按类型</b>比的，
     * {@code 1}（IntNode）与 {@code 1L}（LongNode）、{@code 100000.0}（DoubleNode）与
     * {@code 100000}（IntNode）都不相等。文件里的 id 是整数、导出的行是 long，
     * 用 equals 会让「内容一模一样」被判成「覆盖」，导入结果里全是吓人的覆盖条数。
     *
     * <p>所以数值一律按数值比，布尔与 1/0 也算等（库里 enabled 是 TINYINT，
     * 文件里可能是 boolean），其余按文本比。
     */
    private static boolean same(JsonNode a, JsonNode b) {
        if (a == null || b == null) {
            return a == b;
        }
        boolean aNum = a.isNumber(), bNum = b.isNumber();
        if (aNum || bNum) {
            if (!(aNum && bNum)) {
                // 一边数字一边布尔：true/false 与 1/0 视为同一个值
                return boolLike(a) != null && boolLike(b) != null && boolLike(a).equals(boolLike(b));
            }
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        if (a.isBoolean() || b.isBoolean()) {
            Integer x = boolLike(a), y = boolLike(b);
            if (x == null || y == null) {
                return false;
            }
            return x.equals(y);
        }
        if (a.isObject() && b.isObject()) {
            if (a.size() != b.size()) {
                return false;
            }
            java.util.Iterator<String> it = a.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                if (!b.has(k) || !same(a.get(k), b.get(k))) {
                    return false;
                }
            }
            return true;
        }
        if (a.isArray() && b.isArray()) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!same(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        return a.asText().equals(b.asText());
    }

    /** 布尔或 0/1 折成 0/1；其它值返回 null */
    private static Integer boolLike(JsonNode n) {
        if (n == null) {
            return null;
        }
        if (n.isBoolean()) {
            return n.asBoolean() ? 1 : 0;
        }
        if (n.isNumber()) {
            int v = n.asInt();
            return (v == 0 || v == 1) ? v : null;
        }
        return null;
    }

    /** 空串折成 null：文件里「没填」与库里 NULL 是同一个意思，留着空串会让内容比对永远不等 */
    private static String nullIfEmpty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    private static java.math.BigDecimal decimal(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull()) {
            return java.math.BigDecimal.ZERO;
        }
        if (v.isNumber()) {
            return v.decimalValue();
        }
        try {
            String s = v.asText().trim();
            return s.isEmpty() ? java.math.BigDecimal.ZERO : new java.math.BigDecimal(s);
        } catch (NumberFormatException e) {
            return java.math.BigDecimal.ZERO;
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(String.valueOf(s).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** {@code JsonNode.fields()} 的 Iterable 包装，方便 for-each */
    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        return () -> it;
    }
}
