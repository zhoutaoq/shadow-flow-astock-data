package io.github.zhoutaoq.shadowflow.biz.repo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhoutaoq.shadowflow.biz.config.MarketDataProperties;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.model.StockListItem;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import io.github.zhoutaoq.shadowflow.biz.store.FileTable;
import io.github.zhoutaoq.shadowflow.biz.store.JsonFileIo;
import io.github.zhoutaoq.shadowflow.biz.store.StoreProperties;
import io.github.zhoutaoq.shadowflow.biz.support.AShareCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import io.github.zhoutaoq.shadowflow.biz.store.Reloadable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 无数据库模式：股票列表落 {@code ./data/stock-list.json}，启动时整表载入内存。
 *
 * <p>首次启动（落盘文件还没有）时用 {@code resources/stock-list.json} 播种并立即落盘，
 * 之后一律以 {@code ./data} 下的文件为准 —— 页面上增删改的股票，重启后还在。
 *
 * <p>只收沪深A股（判断口径见 {@link AShareCodes}），非A股代码在播种阶段就丢掉。
 */
@Repository
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileStockListRepository implements StockListRepository, Reloadable {

    private static final Logger log = LoggerFactory.getLogger(FileStockListRepository.class);

    private static final String FILE = "stock-list.json";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileTable<StockListItem> table;
    private final MarketDataProperties props;

    /** 内存全量名录，按代码升序；volatile 整体替换，读侧无锁 */
    private volatile List<StockListItem> rows = new ArrayList<>();
    private volatile long maxId = 0;

    public FileStockListRepository(DataDir dataDir, StoreProperties storeProps, MarketDataProperties props) {
        this.table = new FileTable<>(dataDir, FILE, StockListItem.class, storeProps.getMaxRows());
        this.props = props;
    }

    @PostConstruct
    public synchronized void init() {
        reload();
        if (rows.isEmpty()) {
            List<StockListItem> seed = readSeed();
            if (!seed.isEmpty()) {
                rows = reindex(seed);
                persist();
                log.info("[stock-list] 已用 {} 播种 {} 条沪深A股并落盘到 {}",
                        props.getStockListFile(), rows.size(), table.file());
            } else {
                log.warn("[stock-list] 未找到股票名录种子文件（{}），名录为空，页面只能手输代码",
                        props.getStockListFile());
            }
        } else {
            log.info("[stock-list] 无数据库模式：{} 已载入 {} 条股票", table.file(), rows.size());
        }
    }

    @Override
    public synchronized PageResult<StockListItem> page(String keyword, String market, int page, int size) {
        List<StockListItem> hit = match(keyword, market);
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : size;
        int from = Math.min((p - 1) * s, hit.size());
        int to = Math.min(from + s, hit.size());
        return new PageResult<>(new ArrayList<>(hit.subList(from, to)), hit.size(), p, s);
    }

    @Override
    public synchronized List<StockListItem> search(String keyword, String market, int limit) {
        List<StockListItem> hit = match(keyword, market);
        return hit.size() > limit ? new ArrayList<>(hit.subList(0, limit)) : hit;
    }

    @Override
    public synchronized List<StockListItem> findAll(String market) {
        return match(null, market);
    }

    @Override
    public synchronized List<StockListItem> findByCodes(List<String> codes, String market) {
        if (codes == null || codes.isEmpty()) {
            return new ArrayList<>();
        }
        Set<String> want = new LinkedHashSet<>();
        for (String c : codes) {
            if (c != null && !c.trim().isEmpty()) {
                want.add(c.trim());
            }
        }
        String mk = defaultMarket(market);
        List<StockListItem> out = new ArrayList<>();
        for (StockListItem it : rows) {
            if (want.contains(it.getStockCode()) && mk.equals(defaultMarket(it.getMarket()))) {
                out.add(it);
            }
        }
        return out;
    }

    @Override
    public synchronized StockListItem findById(Long id) {
        if (id == null) {
            return null;
        }
        for (StockListItem it : rows) {
            if (id.equals(it.getId())) {
                return it;
            }
        }
        return null;
    }

    @Override
    public synchronized long insert(StockListItem item) {
        if (item == null || item.getStockCode() == null || item.getStockCode().trim().isEmpty()) {
            throw new IllegalArgumentException("股票代码不能为空");
        }
        String code = item.getStockCode().trim();
        String market = defaultMarket(item.getMarket());
        List<StockListItem> fresh = new ArrayList<>(rows);
        // 同代码 + 同市场已存在时按「更新」处理，避免文件里出现两条重复
        for (StockListItem it : fresh) {
            if (code.equals(it.getStockCode()) && market.equals(defaultMarket(it.getMarket()))) {
                it.setStockName(item.getStockName());
                it.setEnabled(item.getEnabled() == null ? 1 : item.getEnabled());
                it.setUpdateTime(LocalDateTime.now().format(DF));
                rows = sort(fresh);
                persist();
                return it.getId() == null ? 0L : it.getId();
            }
        }
        StockListItem one = new StockListItem();
        one.setId(++maxId);
        one.setStockCode(code);
        one.setStockName(item.getStockName() == null ? "" : item.getStockName().trim());
        one.setMarket(market);
        one.setEnabled(item.getEnabled() == null ? 1 : item.getEnabled());
        String now = LocalDateTime.now().format(DF);
        one.setCreateTime(now);
        one.setUpdateTime(now);
        fresh.add(one);
        rows = sort(fresh);
        persist();
        return one.getId();
    }

    @Override
    public synchronized int update(StockListItem item) {
        if (item == null || item.getId() == null) {
            return 0;
        }
        List<StockListItem> fresh = new ArrayList<>(rows);
        for (int i = 0; i < fresh.size(); i++) {
            StockListItem it = fresh.get(i);
            if (item.getId().equals(it.getId())) {
                it.setStockCode(item.getStockCode());
                it.setStockName(item.getStockName());
                it.setMarket(defaultMarket(item.getMarket()));
                it.setEnabled(item.getEnabled() == null ? 1 : item.getEnabled());
                it.setUpdateTime(LocalDateTime.now().format(DF));
                rows = sort(fresh);
                persist();
                return 1;
            }
        }
        return 0;
    }

    @Override
    public synchronized int delete(Long id) {
        if (id == null) {
            return 0;
        }
        List<StockListItem> fresh = new ArrayList<>(rows.size());
        int removed = 0;
        for (StockListItem it : rows) {
            if (id.equals(it.getId())) {
                removed++;
                continue;
            }
            fresh.add(it);
        }
        if (removed > 0) {
            rows = fresh;
            persist();
        }
        return removed;
    }

    @Override
    public synchronized long countByFilter(String keyword, String market, Integer enabled) {
        return match(keyword, market, enabled).size();
    }

    @Override
    public synchronized List<String> findCodesByFilter(String keyword, String market, Integer enabled, int limit) {
        List<StockListItem> hit = match(keyword, market, enabled);
        List<String> codes = new ArrayList<>(hit.size());
        int n = limit > 0 ? Math.min(limit, hit.size()) : hit.size();
        for (int i = 0; i < n; i++) {
            codes.add(hit.get(i).getStockCode());
        }
        return codes;
    }

    @Override
    public synchronized int upsertBatch(List<StockListItem> items) {
        if (items == null || items.isEmpty()) {
            return 0;
        }
        List<StockListItem> fresh = new ArrayList<>(rows);
        String now = LocalDateTime.now().format(DF);
        int affected = 0;
        for (StockListItem item : items) {
            if (item == null || item.getStockCode() == null || item.getStockCode().trim().isEmpty()) {
                continue;
            }
            String code = item.getStockCode().trim();
            String market = defaultMarket(item.getMarket());
            StockListItem exist = null;
            for (StockListItem it : fresh) {
                if (code.equals(it.getStockCode()) && market.equals(defaultMarket(it.getMarket()))) {
                    exist = it;
                    break;
                }
            }
            if (exist != null) {
                exist.setStockName(item.getStockName());
                exist.setUpdateTime(now);
            } else {
                StockListItem one = new StockListItem();
                one.setId(++maxId);
                one.setStockCode(code);
                one.setStockName(item.getStockName() == null ? "" : item.getStockName().trim());
                one.setMarket(market);
                one.setEnabled(1);
                one.setCreateTime(now);
                one.setUpdateTime(now);
                fresh.add(one);
            }
            affected++;
        }
        rows = sort(fresh);
        persist();
        return affected;
    }

    @Override
    public synchronized long count() {
        return rows.size();
    }

    /** 从落盘文件重新载入内存（手工改了文件时调用） */
    /** 数据迁移导入后重载名录 */
    @Override
    public synchronized void reloadData() {
        reload();
        log.info("[stock-list] 数据迁移后重载名录：{} 条", rows.size());
    }

    public synchronized void reload() {
        List<StockListItem> loaded = table.load();
        rows = reindex(loaded);
    }

    /** 按关键字 + 市场 + 启用状态过滤，并按与数据库一致的口径排序 */
    private List<StockListItem> match(String keyword, String market) {
        return match(keyword, market, null);
    }

    private List<StockListItem> match(String keyword, String market, Integer enabled) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        String mk = StringUtils.hasText(market) ? market.trim() : null;
        List<StockListItem> hit = new ArrayList<>();
        for (StockListItem it : rows) {
            if (mk != null && !mk.equals(defaultMarket(it.getMarket()))) {
                continue;
            }
            if (enabled != null && !enabled.equals(it.getEnabled() == null ? 1 : it.getEnabled())) {
                continue;
            }
            if (!kw.isEmpty() && !contains(it, kw)) {
                continue;
            }
            hit.add(it);
        }
        hit.sort(Comparator.comparingInt((StockListItem it) -> rank(it, kw))
                .thenComparing(StockListItem::getStockCode));
        return hit;
    }

    private static boolean contains(StockListItem it, String lowerKw) {
        String code = it.getStockCode() == null ? "" : it.getStockCode().toLowerCase(Locale.ROOT);
        String name = it.getStockName() == null ? "" : it.getStockName().toLowerCase(Locale.ROOT);
        return code.indexOf(lowerKw) >= 0 || name.indexOf(lowerKw) >= 0;
    }

    /** 完全相等 0 → 代码前缀 1 → 其余 2（与 DbStockListRepository 的 CASE WHEN 对齐） */
    private static int rank(StockListItem it, String lowerKw) {
        if (lowerKw.isEmpty()) {
            return 0;
        }
        String code = it.getStockCode() == null ? "" : it.getStockCode().toLowerCase(Locale.ROOT);
        if (code.equals(lowerKw)) {
            return 0;
        }
        return code.startsWith(lowerKw) ? 1 : 2;
    }

    /** 重新分配内存索引并回收 id 上限（载入 / 播种后调用） */
    private List<StockListItem> reindex(List<StockListItem> loaded) {
        long max = 0;
        List<StockListItem> out = new ArrayList<>(loaded.size());
        for (StockListItem it : loaded) {
            if (it == null || it.getStockCode() == null) {
                continue;
            }
            if (it.getId() == null) {
                it.setId(++max);
            }
            if (it.getId() > max) {
                max = it.getId();
            }
            if (it.getMarket() == null) {
                it.setMarket("stock");
            }
            if (it.getEnabled() == null) {
                it.setEnabled(1);
            }
            out.add(it);
        }
        maxId = max;
        return sort(out);
    }

    private static List<StockListItem> sort(List<StockListItem> in) {
        in.sort(Comparator.comparing(StockListItem::getStockCode));
        return in;
    }

    private void persist() {
        table.save(rows);
    }

    /** 从 resources/stock-list.json 读种子数据（只收沪深A股） */
    private List<StockListItem> readSeed() {
        String json = readSeedFile(props.getStockListFile());
        if (json == null) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = JsonFileIo.mapper().readTree(json);
            if (root == null || !root.isArray()) {
                log.warn("[stock-list] 股票名录种子不是 JSON 数组，已忽略");
                return new ArrayList<>();
            }
            List<StockListItem> out = new ArrayList<>();
            for (JsonNode n : root) {
                String code = n.path("code").asText(null);
                if (code == null || !AShareCodes.isAShare(code.trim())) {
                    continue;
                }
                out.add(new StockListItem(code.trim(), n.path("name").asText("").trim(), "stock"));
            }
            return out;
        } catch (Exception e) {
            log.warn("[stock-list] 股票名录种子解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 依次尝试：配置路径 → 工作目录相对 → 项目根相对 → classpath → classpath 下的文件名 */
    private static String readSeedFile(String configuredPath) {
        String p = StringUtils.hasText(configuredPath) ? configuredPath.trim() : "resources/stock-list.json";
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(p));
        candidates.add(Paths.get(System.getProperty("user.dir"), p));
        Path parent = Paths.get(System.getProperty("user.dir")).getParent();
        if (parent != null) {
            candidates.add(parent.resolve(p));
        }
        candidates.add(Paths.get(System.getProperty("user.dir"), "src", "main", "resources", p));

        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                try {
                    return new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn("[stock-list] 读取失败 {}: {}", c, e.getMessage());
                }
            }
        }
        // classpath 兜底：打进 jar 后文件在 classpath 根目录
        for (String cp : new String[]{p.replace("\\", "/"), baseName(p)}) {
            ClassPathResource res = new ClassPathResource(cp);
            if (!res.exists()) {
                continue;
            }
            try (InputStream in = res.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                StringBuilder sb = new StringBuilder();
                while ((n = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                return sb.toString();
            } catch (Exception ignored) {
                // 换下一个候选
            }
        }
        return null;
    }

    private static String baseName(String p) {
        String s = p.replace("\\", "/");
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }
}
