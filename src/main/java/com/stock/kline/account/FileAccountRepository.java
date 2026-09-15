package com.stock.kline.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stock.kline.store.DataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>文件结构（v1.5.2）：
 * <pre>
 * {
 *   "accounts":  [ { "id":1, "name":"默认账户", "initValue":100000, ... } ],
 *   "positions": { "1": [ { "id":1, "accountId":1, "code":"600000", ... } ] }
 * }
 * </pre>
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

    private final DataDir dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** userId → 该用户的账户文件内容（账户列表 + 每本账的持仓），含内存里的最新值 */
    private final ConcurrentMap<Long, AccountFile> byUser = new ConcurrentHashMap<>();
    /** 账户 id 游标 */
    private final AtomicLong accountSeq = new AtomicLong(0);
    /** 持仓 id 游标 */
    private final AtomicLong positionSeq = new AtomicLong(0);

    public FileAccountRepository(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    @PostConstruct
    public void init() {
        log.info("[account] 无数据库模式：账户与持仓落 {}", dataDir.file("account").toAbsolutePath());
    }

    /* ==================== 账户 ==================== */

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
        if (removed) {
            persist(userId, f);
            log.info("[account] 用户 {} 删除账户 {}（连同其持仓）", userId, accountId);
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

    /* ---------------- 内部实现 ---------------- */

    /** 某本账的持仓容器（可能为空） */
    private static List<Position> rows(AccountFile f, long accountId) {
        List<Position> bucket = f.positions.get(accountId);
        return bucket == null ? new ArrayList<>() : bucket;
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
                log.info("[account] 用户 {} 的账户已载入 {}（{} 本账，{} 条持仓）",
                        userId, p, f.accounts.size(), countPositions(f));
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

    /** 账户按 id 排序、每本账的持仓按代码排序后再落盘，文件 diff 干净 */
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
        return root;
    }

    private static void sortByCode(List<Position> list) {
        list.sort(Comparator.comparing(Position::getCode, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private Path fileOf(long userId) {
        return dataDir.file("account/account-" + userId + ".json");
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
        out.setCreateTime(src.getCreateTime());
        out.setUpdateTime(src.getUpdateTime());
        return out;
    }

    /** 一个用户的账户文件内容：账户列表 + 每本账的持仓，一次读写 */
    public static class AccountFile {
        public List<Account> accounts = new ArrayList<>();
        public Map<Long, List<Position>> positions = new LinkedHashMap<>();
    }
}
