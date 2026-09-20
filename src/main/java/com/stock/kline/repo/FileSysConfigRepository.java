package com.stock.kline.repo;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.SysConfig;
import com.stock.kline.store.DataDir;
import com.stock.kline.store.FileTable;
import com.stock.kline.store.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileSysConfigRepository implements SysConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(FileSysConfigRepository.class);

    private static final String FILE = "sys-config.json";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileTable<SysConfig> table;
    private final WaizaoProperties defaults;

    /** owner|scene|key → 配置行；volatile 整体替换，读侧无锁 */
    private volatile Map<String, SysConfig> rows = new LinkedHashMap<>();
    private volatile long maxId = 0;

    public FileSysConfigRepository(DataDir dataDir, StoreProperties storeProps, WaizaoProperties defaults) {
        this.table = new FileTable<>(dataDir, FILE, SysConfig.class, storeProps.getMaxRows());
        this.defaults = defaults;
    }

    @PostConstruct
    public synchronized void init() {
        reload();
        seedToken();
        log.info("[sys-config] 无数据库模式：{} 已载入 {} 条配置，系统级 token={}",
                table.file(), rows.size(), DbSysConfigRepository.mask(waizaoToken()));
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
    public String waizaoToken() {
        return get(SYSTEM_OWNER, SCENE_WAIZAO, KEY_TOKEN, "");
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

    @Override
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

    /** 文件里还没有系统级 waizao/token 时，用 yml 的值播一次种，省得用户重新找令牌 */
    private void seedToken() {
        if (StringUtils.hasText(waizaoToken())) {
            return;
        }
        String seed = defaults.getApi() == null ? null : defaults.getApi().getToken();
        if (!StringUtils.hasText(seed)) {
            return;
        }
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SYSTEM_OWNER);
        cfg.setConfigScene(SCENE_WAIZAO);
        cfg.setConfigKey(KEY_TOKEN);
        cfg.setConfigValue(seed.trim());
        cfg.setRemark("首次启动自动初始化，请按需更新");
        save(cfg);
        log.info("[sys-config] 已初始化系统级 {}/{}", SCENE_WAIZAO, KEY_TOKEN);
    }

    private void persist() {
        table.save(new ArrayList<>(rows.values()));
    }

    private static String key(long ownerId, String scene, String key) {
        return ownerId + "|" + (scene == null ? "" : scene) + "|" + (key == null ? "" : key);
    }
}
