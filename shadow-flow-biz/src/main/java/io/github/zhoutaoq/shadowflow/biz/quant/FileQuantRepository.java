package io.github.zhoutaoq.shadowflow.biz.quant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * 无数据库模式：量化数据落 {@code ./data/quant/}。
 *
 * <p><b>拆两类文件</b>：
 * <ul>
 *   <li>{@code quant-{userId}.json} —— 策略、持仓、成交、每日快照。量小（一个策略一年 250 条日快照），
 *       整份读写，跨表一致性天然成立；</li>
 *   <li>{@code intraday-{strategyId}-{date}.json} —— 当天日内分时。一天 240 个点，
 *       混进主文件会让每次保存策略配置都重写几百 KB，所以按「策略 + 日期」单独落，并按天淘汰。</li>
 * </ul>
 *
 * <p>写盘一律「整份覆盖」而不是追加：同一分钟的分时点会反复更新（同 key 覆盖），
 * 追加重放反而要额外处理去重与顺序。文件量级都在几十 KB，整写更简单可靠。
 */
@Repository
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileQuantRepository implements QuantRepository {

    private static final Logger log = LoggerFactory.getLogger(FileQuantRepository.class);

    private final DataDir dataDir;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();

    /** userId → 主文件内容（内存最新值 + 写穿到文件） */
    private final ConcurrentMap<Long, QuantFile> byUser = new ConcurrentHashMap<>();

    private final AtomicLong strategySeq = new AtomicLong(0);
    private final AtomicLong holdingSeq = new AtomicLong(0);
    private final AtomicLong tradeSeq = new AtomicLong(0);
    private final AtomicLong dailySeq = new AtomicLong(0);

    public FileQuantRepository(DataDir dataDir) {
        this.dataDir = dataDir;
    }

    @PostConstruct
    public void init() {
        log.info("[quant] 无数据库模式：策略与流水落 {}", dataDir.file("quant").toAbsolutePath());
    }

    /* ==================== 主文件结构 ==================== */

    /** 一个用户的主文件：策略为根，其余按 strategyId 分组 */
    public static class QuantFile {
        public List<QuantStrategy> strategies = new ArrayList<>();
        public Map<String, List<QuantHolding>> holdings = new HashMap<>();
        public Map<String, List<QuantTrade>> trades = new HashMap<>();
        public Map<String, List<QuantDaily>> dailies = new HashMap<>();
    }

    /** 子表接口只带 strategyId，靠它反查所属用户：userId 必须一起带回来，否则会写到错的文件 */
    private static final class Owner {
        final long userId;
        final QuantFile file;

        Owner(long userId, QuantFile file) {
            this.userId = userId;
            this.file = file;
        }
    }

    private Path fileOf(long userId) {
        return dataDir.file("quant/quant-" + userId + ".json");
    }

    private Path intradayFileOf(long strategyId, String date) {
        return dataDir.file("quant/intraday-" + strategyId + "-" + date + ".json");
    }

    private QuantFile load(long userId) {
        QuantFile cached = byUser.get(userId);
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            QuantFile again = byUser.get(userId);
            if (again != null) {
                return again;
            }
            QuantFile f = read(userId);
            for (QuantStrategy s : f.strategies) {
                bump(s.getId(), strategySeq);
            }
            for (List<QuantHolding> bucket : f.holdings.values()) {
                for (QuantHolding h : bucket) {
                    if (h.getId() != null) {
                        bump(h.getId(), holdingSeq);
                    }
                }
            }
            for (List<QuantTrade> bucket : f.trades.values()) {
                for (QuantTrade t : bucket) {
                    if (t.getId() > 0) {
                        bump(t.getId(), tradeSeq);
                    }
                }
            }
            for (List<QuantDaily> bucket : f.dailies.values()) {
                for (QuantDaily d : bucket) {
                    if (d.getId() > 0) {
                        bump(d.getId(), dailySeq);
                    }
                }
            }
            byUser.put(userId, f);
            return f;
        }
    }

    private QuantFile read(long userId) {
        Path p = fileOf(userId);
        if (!Files.exists(p)) {
            return new QuantFile();
        }
        try {
            QuantFile f = mapper.readValue(p.toFile(), QuantFile.class);
            return f == null ? new QuantFile() : f;
        } catch (IOException e) {
            // 文件坏了不能把整个量化模块拖死：留原文件、按空结构启动，用户还能重建策略
            log.warn("[quant] 读取 {} 失败，按空数据处理：{}", p, e.getMessage());
            return new QuantFile();
        }
    }

    private synchronized void persist(long userId, QuantFile f) {
        try {
            writer.writeValue(fileOf(userId).toFile(), f);
        } catch (IOException e) {
            log.error("[quant] 写入用户 {} 的量化数据失败：{}", userId, e.getMessage());
            throw new IllegalStateException("量化数据保存失败：" + e.getMessage(), e);
        }
    }

    private static void bump(long id, AtomicLong seq) {
        seq.updateAndGet(cur -> Math.max(cur, id));
    }

    @SuppressWarnings("unchecked")
    private <T> T copy(T src) {
        if (src == null) {
            return null;
        }
        return (T) mapper.convertValue(src, src.getClass());
    }

    /* ==================== 策略 ==================== */

    @Override
    public List<Long> listStrategyUserIds() {
        Path dir = dataDir.file("quant");
        List<Long> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                if (name.startsWith("quant-") && name.endsWith(".json")) {
                    String mid = name.substring("quant-".length(), name.length() - ".json".length());
                    try {
                        out.add(Long.parseLong(mid));
                    } catch (NumberFormatException ignore) {
                        // 非用户 id 的文件不管
                    }
                }
            });
        } catch (IOException e) {
            log.warn("[quant] 列出用户目录失败：{}", e.getMessage());
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Override
    public List<QuantStrategy> listStrategies(long userId) {
        List<QuantStrategy> out = new ArrayList<>();
        for (QuantStrategy s : load(userId).strategies) {
            out.add(copy(s));
        }
        out.sort(Comparator.comparingLong(QuantStrategy::getId));
        return out;
    }

    @Override
    public List<QuantStrategy> listEnabledStrategies() {
        List<QuantStrategy> out = new ArrayList<>();
        for (Long userId : listStrategyUserIds()) {
            for (QuantStrategy s : load(userId).strategies) {
                if (s.isEnabled()) {
                    out.add(copy(s));
                }
            }
        }
        return out;
    }

    @Override
    public QuantStrategy findStrategy(long userId, long id) {
        for (QuantStrategy s : load(userId).strategies) {
            if (s.getId() == id) {
                return copy(s);
            }
        }
        return null;
    }

    @Override
    public synchronized QuantStrategy createStrategy(QuantStrategy strategy) {
        QuantFile f = load(strategy.getUserId());
        strategy.setId(strategySeq.incrementAndGet());
        f.strategies.add(copy(strategy));
        persist(strategy.getUserId(), f);
        log.info("[quant] 用户 {} 新建策略 {}（{}），初始资金 {}",
                strategy.getUserId(), strategy.getId(), strategy.getName(), strategy.getInitCapital());
        return copy(strategy);
    }

    @Override
    public synchronized void saveStrategy(QuantStrategy strategy) {
        QuantFile f = load(strategy.getUserId());
        boolean replaced = false;
        for (int i = 0; i < f.strategies.size(); i++) {
            if (f.strategies.get(i).getId() == strategy.getId()) {
                f.strategies.set(i, copy(strategy));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (strategy.getId() <= 0) {
                strategy.setId(strategySeq.incrementAndGet());
            }
            f.strategies.add(copy(strategy));
        }
        persist(strategy.getUserId(), f);
    }

    @Override
    public synchronized void deleteStrategy(long userId, long id) {
        QuantFile f = load(userId);
        boolean removed = f.strategies.removeIf(s -> s.getId() == id);
        f.holdings.remove(String.valueOf(id));
        f.trades.remove(String.valueOf(id));
        f.dailies.remove(String.valueOf(id));
        if (!removed) {
            return;
        }
        persist(userId, f);
        purgeIntradayOfStrategy(id);
        log.info("[quant] 用户 {} 删除策略 {}（连同持仓、成交、日快照、分时）", userId, id);
    }

    private void purgeIntradayOfStrategy(long strategyId) {
        Path dir = dataDir.file("quant");
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = "intraday-" + strategyId + "-";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(prefix))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            log.warn("[quant] 删除分时文件 {} 失败：{}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("[quant] 清理策略 {} 的分时文件失败：{}", strategyId, e.getMessage());
        }
    }

    /* ==================== 持仓 ==================== */

    @Override
    public List<QuantHolding> listHoldings(long strategyId) {
        Owner owner = ownerOf(strategyId);
        List<QuantHolding> out = new ArrayList<>();
        for (QuantHolding h : owner.file.holdings.getOrDefault(String.valueOf(strategyId), new ArrayList<>())) {
            out.add(copy(h));
        }
        out.sort(Comparator.comparing(QuantHolding::getCode));
        return out;
    }

    @Override
    public synchronized QuantHolding saveHolding(QuantHolding holding) {
        Owner owner = ownerOf(holding.getStrategyId());
        List<QuantHolding> bucket = owner.file.holdings
                .computeIfAbsent(String.valueOf(holding.getStrategyId()), k -> new ArrayList<>());
        if (holding.getId() == null) {
            holding.setId(holdingSeq.incrementAndGet());
        } else {
            bump(holding.getId(), holdingSeq);
        }
        boolean replaced = false;
        for (int i = 0; i < bucket.size(); i++) {
            QuantHolding cur = bucket.get(i);
            boolean sameId = cur.getId() != null && cur.getId().equals(holding.getId());
            boolean sameCode = cur.getCode() != null && cur.getCode().equals(holding.getCode());
            if (sameId || sameCode) {
                bucket.set(i, copy(holding));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            bucket.add(copy(holding));
        }
        persist(owner.userId, owner.file);
        return copy(holding);
    }

    @Override
    public synchronized void deleteHolding(long strategyId, long id) {
        Owner owner = ownerOf(strategyId);
        List<QuantHolding> bucket = owner.file.holdings.get(String.valueOf(strategyId));
        if (bucket == null || !bucket.removeIf(h -> h.getId() != null && h.getId() == id)) {
            return;
        }
        persist(owner.userId, owner.file);
    }

    /* ==================== 成交 ==================== */

    @Override
    public List<QuantTrade> listTrades(long strategyId, int limit) {
        Owner owner = ownerOf(strategyId);
        List<QuantTrade> out = new ArrayList<>();
        for (QuantTrade t : owner.file.trades.getOrDefault(String.valueOf(strategyId), new ArrayList<>())) {
            out.add(copy(t));
        }
        out.sort(Comparator.comparingLong(QuantTrade::getId).reversed());
        if (limit > 0 && out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    @Override
    public synchronized QuantTrade saveTrade(QuantTrade trade) {
        Owner owner = ownerOf(trade.getStrategyId());
        trade.setId(tradeSeq.incrementAndGet());
        owner.file.trades.computeIfAbsent(String.valueOf(trade.getStrategyId()), k -> new ArrayList<>())
                .add(copy(trade));
        persist(owner.userId, owner.file);
        return copy(trade);
    }

    /* ==================== 每日快照 ==================== */

    @Override
    public QuantDaily findDaily(long strategyId, String tradeDate) {
        Owner owner = ownerOf(strategyId);
        for (QuantDaily d : owner.file.dailies.getOrDefault(String.valueOf(strategyId), new ArrayList<>())) {
            if (tradeDate != null && tradeDate.equals(d.getTradeDate())) {
                return copy(d);
            }
        }
        return null;
    }

    @Override
    public synchronized QuantDaily saveDaily(QuantDaily daily) {
        Owner owner = ownerOf(daily.getStrategyId());
        List<QuantDaily> bucket = owner.file.dailies
                .computeIfAbsent(String.valueOf(daily.getStrategyId()), k -> new ArrayList<>());
        boolean replaced = false;
        for (int i = 0; i < bucket.size(); i++) {
            if (bucket.get(i).getTradeDate().equals(daily.getTradeDate())) {
                if (daily.getId() <= 0) {
                    daily.setId(bucket.get(i).getId());
                }
                bucket.set(i, copy(daily));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            if (daily.getId() <= 0) {
                daily.setId(dailySeq.incrementAndGet());
            }
            bucket.add(copy(daily));
        }
        persist(owner.userId, owner.file);
        return copy(daily);
    }

    @Override
    public List<QuantDaily> listDailies(long strategyId, String from, String to) {
        Owner owner = ownerOf(strategyId);
        List<QuantDaily> out = new ArrayList<>();
        for (QuantDaily d : owner.file.dailies.getOrDefault(String.valueOf(strategyId), new ArrayList<>())) {
            String date = d.getTradeDate();
            if (from != null && !from.isEmpty() && date.compareTo(from) < 0) {
                continue;
            }
            if (to != null && !to.isEmpty() && date.compareTo(to) > 0) {
                continue;
            }
            out.add(copy(d));
        }
        out.sort(Comparator.comparing(QuantDaily::getTradeDate));
        return out;
    }

    /* ==================== 日内分时 ==================== */

    @Override
    public synchronized void saveIntraday(QuantIntradayPoint point) {
        Path p = intradayFileOf(point.getStrategyId(), point.getTradeDate());
        List<QuantIntradayPoint> rows = readIntraday(p);
        boolean replaced = false;
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).getTime().equals(point.getTime())) {
                rows.set(i, point);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            rows.add(point);
        }
        rows.sort(Comparator.comparing(QuantIntradayPoint::getTime));
        try {
            writer.writeValue(p.toFile(), rows);
        } catch (IOException e) {
            log.error("[quant] 写入分时 {} 失败：{}", p, e.getMessage());
        }
    }

    @Override
    public List<QuantIntradayPoint> listIntraday(long strategyId, String tradeDate) {
        return readIntraday(intradayFileOf(strategyId, tradeDate));
    }

    private List<QuantIntradayPoint> readIntraday(Path p) {
        if (!Files.exists(p)) {
            return new ArrayList<>();
        }
        try {
            List<QuantIntradayPoint> rows = mapper.readValue(p.toFile(),
                    new TypeReference<List<QuantIntradayPoint>>() {
                    });
            return rows == null ? new ArrayList<>() : rows;
        } catch (IOException e) {
            log.warn("[quant] 读取分时 {} 失败，按空处理：{}", p, e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public synchronized void purgeIntradayBefore(long strategyId, String tradeDate) {
        Path dir = dataDir.file("quant");
        if (!Files.isDirectory(dir)) {
            return;
        }
        String prefix = "intraday-" + strategyId + "-";
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith(prefix) || !name.endsWith(".json")) {
                    return false;
                }
                String date = name.substring(prefix.length(), name.length() - ".json".length());
                return date.compareTo(tradeDate) < 0;
            }).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("[quant] 删除过期分时 {} 失败：{}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("[quant] 清理过期分时失败：{}", e.getMessage());
        }
    }

    /* ==================== 内部 ==================== */

    /** 按 strategyId 找它所属的用户与文件：子表接口不带 userId，只能反查一次 */
    private Owner ownerOf(long strategyId) {
        for (Map.Entry<Long, QuantFile> e : byUser.entrySet()) {
            if (containsStrategy(e.getValue(), strategyId)) {
                return new Owner(e.getKey(), e.getValue());
            }
        }
        for (Long userId : listStrategyUserIds()) {
            QuantFile f = load(userId);
            if (containsStrategy(f, strategyId)) {
                return new Owner(userId, f);
            }
        }
        throw new IllegalArgumentException("策略不存在：" + strategyId);
    }

    private static boolean containsStrategy(QuantFile f, long strategyId) {
        for (QuantStrategy s : f.strategies) {
            if (s.getId() == strategyId) {
                return true;
            }
        }
        return false;
    }
}
