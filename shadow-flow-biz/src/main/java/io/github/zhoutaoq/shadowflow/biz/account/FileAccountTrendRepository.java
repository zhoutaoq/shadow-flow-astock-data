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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 无数据库模式：账户分钟走势落 {@code ./data/account/trend-{userId}.json}。
 *
 * <p>文件结构（accountId → 日期 → 分钟点）：
 * <pre>
 * {
 *   "1": {
 *     "2026-10-19": [
 *       { "minute": "09:31", "totalValue": 102340.50, "positionValue": 82340.50, "dayProfit": 120.00 }
 *     ]
 *   }
 * }
 * </pre>
 *
 * <p>为什么按用户一个文件而不是每本账一个：账户文件已经是「一个用户一份」，
 * 走势跟着同一棵树走，删账户、清数据都是同一处收口，不会留下孤儿文件。
 *
 * <p>运行期读写内存 + 写穿到文件，与 {@link FileAccountRepository} 完全同一套行为。
 * 内存里按 {@code userId → accountId → date → points} 三层字典存，读某个账户某一天是 O(1)，
 * 不必每次把整份文件扫一遍。
 */
@Repository
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileAccountTrendRepository implements AccountTrendRepository {

    private static final Logger log = LoggerFactory.getLogger(FileAccountTrendRepository.class);

    private static final String FILE_PREFIX = "trend-";
    private static final String FILE_SUFFIX = ".json";

    private final DataDir dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** userId → (accountId → (date → 分钟点列表)) */
    private final ConcurrentMap<Long, Map<Long, Map<String, List<AccountTrendPoint>>>> byUser =
            new ConcurrentHashMap<>();

    public FileAccountTrendRepository(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    @PostConstruct
    public void init() {
        log.info("[account] 无数据库模式：账户分钟走势落 {}", dataDir.file("account").toAbsolutePath());
    }

    @Override
    public synchronized void savePoint(long userId, long accountId, String date, AccountTrendPoint point) {
        if (userId <= 0 || accountId <= 0 || point == null || isBlank(date) || isBlank(point.getMinute())) {
            return;
        }
        List<AccountTrendPoint> bucket = bucket(userId, accountId, date, true);
        boolean replaced = false;
        for (int i = 0; i < bucket.size(); i++) {
            if (point.getMinute().equals(bucket.get(i).getMinute())) {
                bucket.set(i, copy(point));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            bucket.add(copy(point));
        }
        bucket.sort(Comparator.comparing(AccountTrendPoint::getMinute));
        persist(userId);
    }

    @Override
    public synchronized List<AccountTrendPoint> list(long userId, long accountId, String date) {
        List<AccountTrendPoint> out = new ArrayList<>();
        for (AccountTrendPoint p : bucket(userId, accountId, date, false)) {
            out.add(copy(p));
        }
        out.sort(Comparator.comparing(AccountTrendPoint::getMinute));
        return out;
    }

    @Override
    public synchronized int purgeBefore(String date) {
        if (isBlank(date)) {
            return 0;
        }
        int removed = 0;
        for (Long userId : listUserIds()) {
            // 先确保这份文件已载入内存，否则「只删磁盘上的」会让内存里留着旧点、下次写回又回来
            Map<Long, Map<String, List<AccountTrendPoint>>> accounts = load(userId);
            boolean touched = false;
            for (Map<String, List<AccountTrendPoint>> byDate : accounts.values()) {
                Iterator<Map.Entry<String, List<AccountTrendPoint>>> it = byDate.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, List<AccountTrendPoint>> e = it.next();
                    if (e.getKey() != null && e.getKey().compareTo(date) < 0) {
                        removed += e.getValue().size();
                        it.remove();
                        touched = true;
                    }
                }
            }
            if (touched) {
                persist(userId);
            }
        }
        return removed;
    }

    @Override
    public synchronized void deleteAccount(long userId, long accountId) {
        Map<Long, Map<String, List<AccountTrendPoint>>> accounts = load(userId);
        if (accounts.remove(accountId) != null) {
            persist(userId);
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /** 取某个 (用户, 账户, 日期) 的点列表；{@code create=true} 时没有就现建 */
    private List<AccountTrendPoint> bucket(long userId, long accountId, String date, boolean create) {
        Map<Long, Map<String, List<AccountTrendPoint>>> accounts = load(userId);
        Map<String, List<AccountTrendPoint>> byDate = accounts.get(accountId);
        if (byDate == null) {
            if (!create) {
                return new ArrayList<>();
            }
            byDate = new TreeMap<>();
            accounts.put(accountId, byDate);
        }
        List<AccountTrendPoint> bucket = byDate.get(date);
        if (bucket == null) {
            if (!create) {
                return new ArrayList<>();
            }
            bucket = new ArrayList<>();
            byDate.put(date, bucket);
        }
        return bucket;
    }

    private Map<Long, Map<String, List<AccountTrendPoint>>> load(long userId) {
        Map<Long, Map<String, List<AccountTrendPoint>>> f = byUser.get(userId);
        if (f != null) {
            return f;
        }
        Map<Long, Map<String, List<AccountTrendPoint>>> fresh = readFile(userId);
        Map<Long, Map<String, List<AccountTrendPoint>>> prev = byUser.putIfAbsent(userId, fresh);
        return prev == null ? fresh : prev;
    }

    /** 读盘；文件不存在 / 解析失败都给一份空结构（走势丢了大不了少一张图，不该拖垮账户） */
    private Map<Long, Map<String, List<AccountTrendPoint>>> readFile(long userId) {
        Map<Long, Map<String, List<AccountTrendPoint>>> f = new TreeMap<>();
        Path p = fileOf(userId);
        if (!Files.isRegularFile(p)) {
            return f;
        }
        try {
            JsonNode root = mapper.readTree(new String(Files.readAllBytes(p), StandardCharsets.UTF_8));
            if (root == null || !root.isObject()) {
                return f;
            }
            Iterator<Map.Entry<String, JsonNode>> accIt = root.fields();
            while (accIt.hasNext()) {
                Map.Entry<String, JsonNode> acc = accIt.next();
                long accountId = parseLong(acc.getKey());
                if (accountId <= 0 || !acc.getValue().isObject()) {
                    continue;
                }
                Map<String, List<AccountTrendPoint>> byDate = new TreeMap<>();
                Iterator<Map.Entry<String, JsonNode>> dateIt = acc.getValue().fields();
                while (dateIt.hasNext()) {
                    Map.Entry<String, JsonNode> d = dateIt.next();
                    if (!d.getValue().isArray()) {
                        continue;
                    }
                    List<AccountTrendPoint> points = new ArrayList<>();
                    for (JsonNode n : d.getValue()) {
                        AccountTrendPoint point = mapper.treeToValue(n, AccountTrendPoint.class);
                        if (point != null && !isBlank(point.getMinute())) {
                            points.add(point);
                        }
                    }
                    points.sort(Comparator.comparing(AccountTrendPoint::getMinute));
                    byDate.put(d.getKey(), points);
                }
                f.put(accountId, byDate);
            }
            log.info("[account] 用户 {} 的账户走势已载入 {}（{} 本账，{} 个交易日）",
                    userId, p, f.size(), countDates(f));
        } catch (Exception e) {
            log.warn("[account] 走势文件解析失败 {}：{}，按空数据继续", p, e.getMessage());
        }
        return f;
    }

    /** 整文件写回；账户按 id 排序、日期升序、分钟升序，文件 diff 干净 */
    private void persist(long userId) {
        Path p = fileOf(userId);
        Map<Long, Map<String, List<AccountTrendPoint>>> f = byUser.get(userId);
        if (f == null) {
            return;
        }
        ObjectNode root = mapper.createObjectNode();
        for (Map.Entry<Long, Map<String, List<AccountTrendPoint>>> acc : new TreeMap<>(f).entrySet()) {
            ObjectNode byDate = root.putObject(String.valueOf(acc.getKey()));
            for (Map.Entry<String, List<AccountTrendPoint>> d : new TreeMap<>(acc.getValue()).entrySet()) {
                if (d.getValue().isEmpty()) {
                    continue;
                }
                List<AccountTrendPoint> points = new ArrayList<>(d.getValue());
                points.sort(Comparator.comparing(AccountTrendPoint::getMinute));
                ArrayNode arr = byDate.putArray(d.getKey());
                for (AccountTrendPoint point : points) {
                    arr.add(mapper.valueToTree(point));
                }
            }
        }
        try {
            Path parent = p.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(p, writer.writeValueAsString(root).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[account] 走势落盘失败 {}：{}（内存里的值仍然生效）", p, e.getMessage());
        }
    }

    /** 有走势文件的全部用户 id（purge 要跨用户遍历） */
    private List<Long> listUserIds() {
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
                log.warn("[account] 走势目录扫描失败 {}：{}", dir, e.getMessage());
            }
        }
        return new ArrayList<>(ids);
    }

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

    private static int countDates(Map<Long, Map<String, List<AccountTrendPoint>>> f) {
        int n = 0;
        for (Map<String, List<AccountTrendPoint>> byDate : f.values()) {
            n += byDate.size();
        }
        return n;
    }

    private Path fileOf(long userId) {
        return dataDir.file("account/" + FILE_PREFIX + userId + FILE_SUFFIX);
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(String.valueOf(s).trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private static AccountTrendPoint copy(AccountTrendPoint src) {
        return new AccountTrendPoint(src.getMinute(), src.getTotalValue(),
                src.getPositionValue(), src.getDayProfit());
    }
}
