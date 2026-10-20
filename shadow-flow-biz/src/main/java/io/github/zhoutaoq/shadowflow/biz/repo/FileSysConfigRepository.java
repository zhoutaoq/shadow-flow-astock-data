package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.config.MarketDataProperties;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import io.github.zhoutaoq.shadowflow.biz.store.FileTable;
import io.github.zhoutaoq.shadowflow.biz.store.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import io.github.zhoutaoq.shadowflow.biz.store.Reloadable;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无数据库模式：配置落 {@code ./data/sys-config.json}，启动时整表载入内存。
 *
 * <p>与 {@link DbSysConfigRepository} 暴露完全相同的行为：页面照常增删改查，
 * 保存后立即生效，重启后从文件恢复。令牌也就有了稳定的落点，
 * 不必每次都靠浏览器把它带在请求头里。
 *
 * <p>v1.5 起同样按用户隔离，索引键为「归属用户 + 场景 + 键」。
 */
@Repository
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileSysConfigRepository implements SysConfigRepository, Reloadable {

    private static final Logger log = LoggerFactory.getLogger(FileSysConfigRepository.class);

    private static final String FILE = "sys-config.json";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileTable<SysConfig> table;
    private final MarketDataProperties defaults;

    /** owner|scene|key → 配置行；volatile 整体替换，读侧无锁 */
    private volatile Map<String, SysConfig> rows = new LinkedHashMap<>();
    private volatile long maxId = 0;

    public FileSysConfigRepository(DataDir dataDir, StoreProperties storeProps, MarketDataProperties defaults) {
        this.table = new FileTable<>(dataDir, FILE, SysConfig.class, storeProps.getMaxRows());
        this.defaults = defaults;
    }

    @PostConstruct
    public synchronized void init() {
        reload();
        migrateLegacyScene();
        seedToken();
        log.info("[sys-config] 无数据库模式：{} 已载入 {} 条配置，系统级 token={}",
                table.file(), rows.size(), DbSysConfigRepository.mask(dataApiToken()));
    }

    /**
     * 场景值改写（v1.6.0）：文件里 {@code waizao} 场景的行改成 {@code shadowflow}。
     *
     * <p>数据库模式那边由 {@code ShadowFlowDbInitializer} 做同一件事；文件模式没有 SQL 可用，
     * 只能在这里就地改。改完立刻落盘，所以只在升级后第一次启动时生效一次 —— 幂等。
     *
     * <p>两趟走：先把非旧场景的行原样铺好，再搬旧行。同一 owner + key 已有新场景行时
     * 旧行直接丢弃（与读取侧「新值优先」的口径一致）；一趟走会在旧行先出现时把新行盖掉。
     */
    private void migrateLegacyScene() {
        Map<String, SysConfig> fresh = new LinkedHashMap<>();
        for (SysConfig c : rows.values()) {
            if (SCENE_DATA_API_LEGACY.equals(c.getConfigScene())) {
                continue;
            }
            fresh.put(key(c.getOwnerId(), c.getConfigScene(), c.getConfigKey()), c);
        }
        int moved = 0;
        int dropped = 0;
        for (SysConfig c : rows.values()) {
            if (!SCENE_DATA_API_LEGACY.equals(c.getConfigScene())) {
                continue;
            }
            String neoKey = key(c.getOwnerId(), SCENE_DATA_API, c.getConfigKey());
            if (fresh.containsKey(neoKey)) {
                dropped++;
                continue;
            }
            c.setConfigScene(SCENE_DATA_API);
            fresh.put(neoKey, c);
            moved++;
        }
        if (moved == 0 && dropped == 0) {
            return;
        }
        rows = fresh;
        persist();
        log.info("[sys-config] 场景值 {} → {}：改写 {} 行，去重删除 {} 行",
                SCENE_DATA_API_LEGACY, SCENE_DATA_API, moved, dropped);
    }

    @Override
    public String get(long ownerId, String scene, String key) {
        SysConfig c = rows.get(key(ownerId, scene, key));
        return c == null ? null : c.getConfigValue();
    }

    @Override
    public String get(long ownerId, String scene, String key, String def) {
        String v = get(ownerId, scene, key);
        return StringUtils.hasText(v) ? v : def;
    }

    @Override
    public String get(String scene, String key) {
        return get(SYSTEM_OWNER, scene, key);
    }

    @Override
    public String dataApiToken() {
        // 新场景值优先，读不到再认旧场景值 —— 升级后既有令牌不能凭空消失
        String v = get(SYSTEM_OWNER, SCENE_DATA_API, KEY_TOKEN, "");
        return StringUtils.hasText(v) ? v : get(SYSTEM_OWNER, SCENE_DATA_API_LEGACY, KEY_TOKEN, "");
    }

    @Override
    public synchronized List<SysConfig> list(long ownerId) {
        List<SysConfig> out = new ArrayList<>();
        for (SysConfig c : rows.values()) {
            if (c.getOwnerId() == ownerId) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(SysConfig::getConfigScene).thenComparing(SysConfig::getConfigKey));
        return out;
    }

    @Override
    public synchronized void save(SysConfig cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("配置内容不能为空");
        }
        String scene = cfg.getConfigScene() == null ? "" : cfg.getConfigScene().trim();
        String key = cfg.getConfigKey() == null ? "" : cfg.getConfigKey().trim();
        if (!StringUtils.hasText(scene) || !StringUtils.hasText(key)) {
            throw new IllegalArgumentException("场景与配置键都不能为空");
        }
        long owner = cfg.getOwnerId();
        String now = LocalDateTime.now().format(DF);
        Map<String, SysConfig> fresh = new LinkedHashMap<>(rows);
        SysConfig exist = fresh.get(key(owner, scene, key));
        if (exist == null) {
            exist = new SysConfig();
            exist.setId(++maxId);
            exist.setOwnerId(owner);
            exist.setConfigScene(scene);
            exist.setConfigKey(key);
            exist.setCreateTime(now);
        }
        exist.setConfigValue(cfg.getConfigValue() == null ? "" : cfg.getConfigValue().trim());
        exist.setRemark(cfg.getRemark());
        exist.setUpdateTime(now);
        fresh.put(key(owner, scene, key), exist);
        rows = fresh;
        persist();
        log.info("[sys-config] 保存 owner={} {}/{} 已生效", owner, scene, key);
    }

    @Override
    public synchronized void delete(long ownerId, long id) {
        Map<String, SysConfig> fresh = new LinkedHashMap<>();
        boolean hit = false;
        for (Map.Entry<String, SysConfig> e : rows.entrySet()) {
            SysConfig row = e.getValue();
            // 归属一起比对：删不动别人的配置行
            if (row.getOwnerId() == ownerId && row.getId() != null && row.getId() == id) {
                hit = true;
                continue;
            }
            fresh.put(e.getKey(), row);
        }
        if (!hit) {
            return;
        }
        rows = fresh;
        persist();
    }

    /** 数据迁移导入后重载：配置行被改写，内存索引与场景值改写都要重跑一遍 */
    @Override
    public synchronized void reloadData() {
        reload();
        migrateLegacyScene();
        seedToken();
        log.info("[sys-config] 数据迁移后重载配置：{} 条", rows.size());
    }

    public synchronized void reload() {
        Map<String, SysConfig> fresh = new LinkedHashMap<>();
        long max = 0;
        for (SysConfig c : table.load()) {
            if (c == null || !StringUtils.hasText(c.getConfigScene()) || !StringUtils.hasText(c.getConfigKey())) {
                continue;
            }
            if (c.getId() != null && c.getId() > max) {
                max = c.getId();
            }
            fresh.put(key(c.getOwnerId(), c.getConfigScene(), c.getConfigKey()), c);
        }
        maxId = max;
        rows = fresh;
    }

    /** 文件里还没有系统级行情数据服务 token 时，用 yml 的值播一次种，省得用户重新找令牌 */
    private void seedToken() {
        if (StringUtils.hasText(dataApiToken())) {
            return;
        }
        String seed = defaults.getApi() == null ? null : defaults.getApi().getToken();
        if (!StringUtils.hasText(seed)) {
            return;
        }
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SYSTEM_OWNER);
        cfg.setConfigScene(SCENE_DATA_API);
        cfg.setConfigKey(KEY_TOKEN);
        cfg.setConfigValue(seed.trim());
        cfg.setRemark("首次启动自动初始化，请按需更新");
        save(cfg);
        log.info("[sys-config] 已初始化系统级 {}/{}", SCENE_DATA_API, KEY_TOKEN);
    }

    private void persist() {
        table.save(new ArrayList<>(rows.values()));
    }

    private static String key(long ownerId, String scene, String key) {
        return ownerId + "|" + (scene == null ? "" : scene) + "|" + (key == null ? "" : key);
    }
}
