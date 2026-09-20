package io.github.zhoutaoq.shadowflow.biz.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 无数据库模式：一个用户的<b>全部账户与持仓</b>落 {@code ./data/account/account-{userId}.json}。
 *
 * <p>每个用户一个文件、单次整文件读写 —— 账户与持仓是一棵两层的树（账户 → 持仓），
 * 放同一个文件里可以整份读整份写，不必再像 SysConfig 那样维护内存索引。
 * 运行期读写内存 + 写穿到文件，与其它 File 仓储保持同一套行为：
 * 保存后立即生效、重启后从文件恢复。
 *
 * <p>文件结构（v1.5.2 建立，v1.5.5 补建仓日与当天买入池，v1.5.6 补可用数量与操作记录）：
 * <pre>
 * {
 *   "accounts":  [ { "id":1, "name":"默认账户", "initValue":100000,
 *                    "dayRealizedDate":"2026-09-16", "dayRealizedProfit":320.5 } ],
 *   "positions": { "1": [ { "id":1, "accountId":1, "code":"600000", "openDate":"2026-09-16",
 *                           "availableQty":800,
 *                           "dayBuyDate":"2026-09-16", "dayBuyQty":200, "dayBuyAmount":2100 } ] },
 *   "records":   { "1": [ { "id":1, "accountId":1, "action":"buy", "code":"600000",
 *                           "price":9.0, "quantity":1000, "tradeDate":"2026-09-16",
 *                           "time":"2026-09-16 10:12:33" } ] }
 * }
 * </pre>
 * 老文件里没有的新字段反序列化时保持字段默认值（0 / null），等价于「没记过当天买入」、
 * 「持仓数量全部可用」、「没有操作记录」。
 *
 * <p><b>兼容 v1.5.1 老文件</b>（{@code {"account":{...},"positions":{"1":{...}}}}）：
 * 读到时自动收敛成「一本名为『默认账户』的账」，老持仓全部挂到这本账下 ——
 * 用户升级后看到的就是原来的那份数据，只是多了一层账户外壳。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileAccountRepository implements AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(FileAccountRepository.class);

    /** 老文件里那本账被收敛成账户后用的名字 */
    private static final String LEGACY_ACCOUNT_NAME = "默认账户";

    /** 账户文件名前缀，{@code account-{userId}.json} */
    private static final String FILE_PREFIX = "account-";

    /** 账户文件名后缀 */
    private static final String FILE_SUFFIX = ".json";

    private final DataDir dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** userId → 该用户的账户文件内容（账户列表 + 每本账的持仓与操作记录），含内存里的最新值 */
    private final ConcurrentMap<Long, AccountFile> byUser = new ConcurrentHashMap<>();
    /** 账户 id 游标 */
    private final AtomicLong accountSeq = new AtomicLong(0);
    /** 持仓 id 游标 */
    private final AtomicLong positionSeq = new AtomicLong(0);
    /** 操作记录 id 游标 */
    private final AtomicLong recordSeq = new AtomicLong(0);

    public FileAccountRepository(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    @PostConstruct
    public void init() {
        log.info("[account] 无数据库模式：账户 / 持仓 / 操作记录落 {}", dataDir.file("account").toAbsolutePath());
    }

    /* ==================== 账户 ==================== */

    /**
     * {@inheritDoc}
     *
     * <p>内存里已载入的与磁盘上已有文件的取并集：定时任务可能在「进程起来后还没有任何请求
     * 碰过账户」的时刻就触发，只看 {@code byUser} 会把还没载入的文件整个漏掉。
     */
    @Override
    public List<Long> listUserIds() {
        Set<Long> ids = new TreeSet<>(byUser.keySet());
        Path dir = dataDir.root().resolve("account");
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, FILE_PREFIX + "*" + FILE_SUFFIX)) {
                for (Path p : stream) {
                    Long id = userIdOf(p.getFileName().toString());
                    if (id != null) {
                        ids.add(id);
                    }
                }
            } catch (IOException e) {
                log.warn("[account] 账户目录扫描失败 {}：{}", dir, e.getMessage());
            }
        }
        return new ArrayList<>(ids);
    }

    /** 从 {@code account-{userId}.json} 的文件名里取出 userId；名字不合规返回 null */
    private static Long userIdOf(String fileName) {
        if (fileName == null || !fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) {
            return null;
        }
        String mid = fileName.substring(FILE_PREFIX.length(), fileName.length() - FILE_SUFFIX.length());
        try {
            return Long.parseLong(mid);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public List<Account> listAccounts(long userId) {
        List<Account> out = new ArrayList<>();
        for (Account a : load(userId).accounts) {
            out.add(copyAccount(a));
        }
        out.sort(Comparator.comparingLong(Account::getId));
        return out;
    }

    @Override
    public Account findAccount(long userId, long accountId) {
        for (Account a : load(userId).accounts) {
            if (a.getId() == accountId) {
                return copyAccount(a);
            }
        }
        return null;
    }

    @Override
    public synchronized Account createAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        AccountFile f = load(account.getUserId());
        account.setId(accountSeq.incrementAndGet());
        f.accounts.add(copyAccount(account));
        persist(account.getUserId(), f);
        log.info("[account] 用户 {} 新建账户 {}（{}）", account.getUserId(), account.getId(), account.getName());
        return copyAccount(account);
    }

    @Override
    public synchronized void saveAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        AccountFile f = load(account.getUserId());
        boolean replaced = false;
        for (int i = 0; i < f.accounts.size(); i++) {
            if (f.accounts.get(i).getId() == account.getId()) {
                f.accounts.set(i, copyAccount(account));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (account.getId() <= 0) {
                account.setId(accountSeq.incrementAndGet());
            }
            f.accounts.add(copyAccount(account));
        }
        persist(account.getUserId(), f);
        log.info("[account] 用户 {} 账户 {}（{}）已保存：期初市值 {}，可用金额 {}",
                account.getUserId(), account.getId(), account.getName(),
                account.getInitValue(), account.getCash());
    }

    @Override
    public synchronized void deleteAccount(long userId, long accountId) {
        AccountFile f = load(userId);
        boolean removed = f.accounts.removeIf(a -> a.getId() == accountId);
        f.positions.remove(accountId);
        f.records.remove(accountId);
        if (removed) {
            persist(userId, f);
            log.info("[account] 用户 {} 删除账户 {}（连同其持仓与操作记录）", userId, accountId);
        }
    }

    /* ==================== 持仓 ==================== */

    @Override
    public List<Position> listPositions(long userId, long accountId) {
        List<Position> out = new ArrayList<>();
        for (Position p : rows(load(userId), accountId)) {
            out.add(copyPosition(p));
        }
        sortByCode(out);
        return out;
    }

    @Override
    public synchronized Position savePosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("持仓内容不能为空");
        }
        AccountFile f = load(position.getUserId());
        if (position.getId() == null) {
            position.setId(positionSeq.incrementAndGet());
        } else {
            // 前端可能沿用上一份快照里的 id 提交，这里让游标跨过它，避免后续新增撞 id
            long id = position.getId();
            positionSeq.updateAndGet(cur -> Math.max(cur, id));
        }
        List<Position> bucket = f.positions.computeIfAbsent(position.getAccountId(), k -> new ArrayList<>());
        boolean replaced = false;
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.get(i).getId() != null && bucket.get(i).getId().equals(position.getId())) {
                bucket.set(i, copyPosition(position));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            bucket.add(copyPosition(position));
        }
        persist(position.getUserId(), f);
        return copyPosition(position);
    }

    @Override
    public synchronized void deletePosition(long userId, long accountId, long id) {
        AccountFile f = load(userId);
        List<Position> bucket = f.positions.get(accountId);
        if (bucket == null || !bucket.removeIf(p -> p.getId() != null && p.getId() == id)) {
            return;
        }
        persist(userId, f);
    }

    @Override
    public synchronized void deleteAllPositions(long userId, long accountId) {
        AccountFile f = load(userId);
        List<Position> bucket = f.positions.get(accountId);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        f.positions.put(accountId, new ArrayList<>());
        persist(userId, f);
    }

    /* ==================== 操作记录（v1.5.6 起落盘） ==================== */

    @Override
    public List<TradeRecord> listRecords(long userId, long accountId) {
        List<TradeRecord> out = new ArrayList<>();
        for (TradeRecord r : recordRows(load(userId), accountId)) {
            out.add(copyRecord(r));
        }
        out.sort(Comparator.comparingLong(TradeRecord::getId));
        return out;
    }

    @Override
    public synchronized TradeRecord saveRecord(TradeRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("操作记录不能为空");
        }
        AccountFile f = load(record.getUserId());
        long id = record.getId() > 0 ? record.getId() : recordSeq.incrementAndGet();
        if (record.getId() > 0) {
            // 前端可能沿用旧快照里的 id，让游标跨过去，后续新增不会撞 id
            recordSeq.updateAndGet(cur -> Math.max(cur, record.getId()));
        }
        record.setId(id);
        recordBucket(f, record.getAccountId()).add(copyRecord(record));
        persist(record.getUserId(), f);
        return copyRecord(record);
    }

    @Override
    public synchronized void trimRecords(long userId, long accountId, int keep) {
        AccountFile f = load(userId);
        List<TradeRecord> bucket = f.records.get(accountId);
        if (bucket == null || bucket.size() <= keep) {
            return;
        }
        bucket.sort(Comparator.comparingLong(TradeRecord::getId));
        while (bucket.size() > keep) {
            bucket.remove(0);
        }
        persist(userId, f);
    }

    @Override
    public synchronized void clearRecords(long userId, long accountId) {
        AccountFile f = load(userId);
        List<TradeRecord> bucket = f.records.get(accountId);
        if (bucket == null || bucket.isEmpty()) {
            return;
        }
        f.records.put(accountId, new ArrayList<>());
        persist(userId, f);
    }

    /* ---------------- 内部实现 ---------------- */

    /** 某本账的持仓容器（可能为空） */
    private static List<Position> rows(AccountFile f, long accountId) {
        List<Position> bucket = f.positions.get(accountId);
        return bucket == null ? new ArrayList<>() : bucket;
    }

    /** 某本账的操作记录容器（可能为空；只读场景用，不会往 map 里塞空列表） */
    private static List<TradeRecord> recordRows(AccountFile f, long accountId) {
        List<TradeRecord> bucket = f.records.get(accountId);
        return bucket == null ? new ArrayList<>() : bucket;
    }

    /** 某本账的操作记录容器（写场景用：没有就现建一个） */
    private static List<TradeRecord> recordBucket(AccountFile f, long accountId) {
        return f.records.computeIfAbsent(accountId, k -> new ArrayList<>());
    }

    /** 载入（必要时从文件读一次）某用户的账户文件；文件不存在给一份空结构 */
    private AccountFile load(long userId) {
        AccountFile f = byUser.get(userId);
        if (f != null) {
            return f;
        }
        AccountFile fresh = readFile(userId);
        AccountFile prev = byUser.putIfAbsent(userId, fresh);
        return prev == null ? fresh : prev;
    }

    private AccountFile readFile(long userId) {
        AccountFile f = new AccountFile();
        Path p = fileOf(userId);
        if (!Files.isRegularFile(p)) {
            return f;
        }
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            if (root == null || root.isNull() || !root.isObject()) {
                return f;
            }
            JsonNode accounts = root.get("accounts");
            if (accounts != null && accounts.isArray()) {
                readNewFormat(f, accounts, root.get("positions"));
                readRecords(f, userId, root.get("records"));
                log.info("[account] 用户 {} 的账户已载入 {}（{} 本账，{} 条持仓，{} 条操作记录）",
                        userId, p, f.accounts.size(), countPositions(f), countRecords(f));
            } else {
                readLegacyFormat(f, root);
                if (countPositions(f) > 0 || root.get("account") != null) {
                    log.info("[account] 用户 {} 的 v1.5.1 老账户文件已升级为账户维度 {}（1 本账，{} 条持仓）",
                            userId, p, countPositions(f));
                }
            }
            // 游标要跨过文件里已有的 id，否则新建会撞上旧 id
            for (Account a : f.accounts) {
                bump(a.getId(), accountSeq);
            }
            for (List<TradeRecord> bucket : f.records.values()) {
                for (TradeRecord r : bucket) {
                    bump(r.getId(), recordSeq);
                }
            }
        } catch (Exception e) {
            log.warn("[account] 账户文件解析失败 {}：{}，按空账户继续", p, e.getMessage());
        }
        return f;
    }

    /** v1.5.2 结构：accounts[] + positions{accountId: [...]} */
    private void readNewFormat(AccountFile f, JsonNode accounts, JsonNode positions) throws IOException {
        for (JsonNode n : accounts) {
            Account a = mapper.treeToValue(n, Account.class);
            if (a != null) {
                f.accounts.add(a);
            }
        }
        if (positions == null || !positions.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = positions.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            long accountId = parseLong(e.getKey());
            List<Position> bucket = f.positions.computeIfAbsent(accountId, k -> new ArrayList<>());
            JsonNode arr = e.getValue();
            if (arr == null || !arr.isArray()) {
                continue;
            }
            for (JsonNode pn : arr) {
                Position pos = mapper.treeToValue(pn, Position.class);
                if (pos == null) {
                    continue;
                }
                normalizeIds(pos, accountId);
                bucket.add(pos);
            }
        }
    }

    /** v1.5.6：操作记录段 {@code records{accountId: [...]}}；没有这一段就是「没有记录」 */
    private void readRecords(AccountFile f, long userId, JsonNode records) throws IOException {
        if (records == null || !records.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = records.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            long accountId = parseLong(e.getKey());
            JsonNode arr = e.getValue();
            if (arr == null || !arr.isArray()) {
                continue;
            }
            List<TradeRecord> bucket = f.records.computeIfAbsent(accountId, k -> new ArrayList<>());
            for (JsonNode rn : arr) {
                TradeRecord r = mapper.treeToValue(rn, TradeRecord.class);
                if (r == null) {
                    continue;
                }
                if (r.getId() <= 0) {
                    r.setId(recordSeq.incrementAndGet());
                }
                if (r.getAccountId() <= 0) {
                    r.setAccountId(accountId);
                }
                r.setUserId(userId);
                bucket.add(r);
            }
        }
    }

    /**
     * v1.5.1 老结构：单本账 + 持仓按 id 直接摊平。
     *
     * <p>收敛规则：老账户原样搬过来、只补一个名字，老持仓统一挂到这本账下 ——
     * 用户升级后数字与原来完全一致。
     */
    private void readLegacyFormat(AccountFile f, JsonNode root) throws IOException {
        long accountId = 1L;
        JsonNode legacy = root.get("account");
        Account a = (legacy != null && legacy.isObject()) ? mapper.treeToValue(legacy, Account.class) : null;
        if (a == null) {
            a = new Account();
        }
        a.setId(accountId);
        if (a.getName() == null || a.getName().trim().isEmpty()) {
            a.setName(LEGACY_ACCOUNT_NAME);
        }
        f.accounts.add(a);

        List<Position> bucket = f.positions.computeIfAbsent(accountId, k -> new ArrayList<>());
        JsonNode positions = root.get("positions");
        if (positions == null || !positions.isObject()) {
            return;
        }
        Iterator<Map.Entry<String, JsonNode>> it = positions.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode n = e.getValue();
            if (n == null || !n.isObject()) {
                continue;
            }
            Position pos = mapper.treeToValue(n, Position.class);
            if (pos == null) {
                continue;
            }
            if (pos.getId() == null) {
                long id = parseLong(e.getKey());
                pos.setId(id > 0 ? id : positionSeq.incrementAndGet());
            }
            normalizeIds(pos, accountId);
            bucket.add(pos);
        }
    }

    /** 补齐 id / 归属，并把两个游标推过已用过的值 */
    private void normalizeIds(Position pos, long accountId) {
        if (pos.getId() == null) {
            pos.setId(positionSeq.incrementAndGet());
        } else {
            long id = pos.getId();
            positionSeq.updateAndGet(cur -> Math.max(cur, id));
        }
        if (pos.getAccountId() <= 0) {
            pos.setAccountId(accountId);
        }
    }

    private int countPositions(AccountFile f) {
        int n = 0;
        for (List<Position> bucket : f.positions.values()) {
            n += bucket.size();
        }
        return n;
    }

    private int countRecords(AccountFile f) {
        int n = 0;
        for (List<TradeRecord> bucket : f.records.values()) {
            n += bucket.size();
        }
        return n;
    }

    private void persist(long userId, AccountFile f) {
        Path p = fileOf(userId);
        try {
            Path parent = p.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(p, writer.writeValueAsString(toNode(f)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 落盘失败不影响内存里已经生效的值，与其它 File 仓储同口径
            log.warn("[account] 账户落盘失败 {}：{}（内存里的值仍然生效）", p, e.getMessage());
        }
    }

    /** 账户按 id 排序、每本账的持仓按代码排序、操作记录按 id 排序后再落盘，文件 diff 干净 */
    private ObjectNode toNode(AccountFile f) {
        ObjectNode root = mapper.createObjectNode();
        List<Account> accounts = new ArrayList<>(f.accounts);
        accounts.sort(Comparator.comparingLong(Account::getId));
        ArrayNode accArr = root.putArray("accounts");
        for (Account a : accounts) {
            accArr.add(mapper.valueToTree(a));
        }
        ObjectNode posObj = root.putObject("positions");
        for (Map.Entry<Long, List<Position>> e : f.positions.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<Position> list = new ArrayList<>(e.getValue());
            sortByCode(list);
            ArrayNode arr = posObj.putArray(String.valueOf(e.getKey()));
            for (Position p : list) {
                arr.add(mapper.valueToTree(p));
            }
        }
        ObjectNode recObj = root.putObject("records");
        for (Map.Entry<Long, List<TradeRecord>> e : f.records.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<TradeRecord> list = new ArrayList<>(e.getValue());
            list.sort(Comparator.comparingLong(TradeRecord::getId));
            ArrayNode arr = recObj.putArray(String.valueOf(e.getKey()));
            for (TradeRecord r : list) {
                arr.add(mapper.valueToTree(r));
            }
        }
        return root;
    }

    private static void sortByCode(List<Position> list) {
        list.sort(Comparator.comparing(Position::getCode, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private Path fileOf(long userId) {
        return dataDir.file("account/" + FILE_PREFIX + userId + FILE_SUFFIX);
    }

    private static void bump(long value, AtomicLong seq) {
        if (value > 0) {
            seq.updateAndGet(cur -> Math.max(cur, value));
        }
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(String.valueOf(s).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static Account copyAccount(Account src) {
        Account out = new Account();
        out.setId(src.getId());
        out.setUserId(src.getUserId());
        out.setName(src.getName());
        out.setInitValue(src.getInitValue());
        out.setCash(src.getCash());
        out.setDayRealizedDate(src.getDayRealizedDate());
        out.setDayRealizedProfit(src.getDayRealizedProfit());
        out.setRemark(src.getRemark());
        out.setCreateTime(src.getCreateTime());
        out.setUpdateTime(src.getUpdateTime());
        return out;
    }

    private static Position copyPosition(Position src) {
        Position out = new Position();
        out.setId(src.getId());
        out.setUserId(src.getUserId());
        out.setAccountId(src.getAccountId());
        out.setCode(src.getCode());
        out.setName(src.getName());
        out.setCostPrice(src.getCostPrice());
        out.setQuantity(src.getQuantity());
        out.setAvailableQty(src.getAvailableQty());
        out.setOpenDate(src.getOpenDate());
        out.setDayBuyDate(src.getDayBuyDate());
        out.setDayBuyQty(src.getDayBuyQty());
        out.setDayBuyAmount(src.getDayBuyAmount());
        out.setCreateTime(src.getCreateTime());
        out.setUpdateTime(src.getUpdateTime());
        return out;
    }

    private static TradeRecord copyRecord(TradeRecord src) {
        TradeRecord out = new TradeRecord();
        out.setId(src.getId());
        out.setUserId(src.getUserId());
        out.setAccountId(src.getAccountId());
        out.setAction(src.getAction());
        out.setCode(src.getCode());
        out.setName(src.getName());
        out.setPrice(src.getPrice());
        out.setQuantity(src.getQuantity());
        out.setTurnover(src.getTurnover());
        out.setCashAfter(src.getCashAfter());
        out.setSameDay(src.isSameDay());
        out.setRealizedProfit(src.getRealizedProfit());
        out.setTradeDate(src.getTradeDate());
        out.setTime(src.getTime());
        return out;
    }

    /** 一个用户的账户文件内容：账户列表 + 每本账的持仓与操作记录，一次读写 */
    public static class AccountFile {
        public List<Account> accounts = new ArrayList<>();
        public Map<Long, List<Position>> positions = new LinkedHashMap<>();
        /** v1.5.6：每本账的操作记录 */
        public Map<Long, List<TradeRecord>> records = new LinkedHashMap<>();
    }
}
