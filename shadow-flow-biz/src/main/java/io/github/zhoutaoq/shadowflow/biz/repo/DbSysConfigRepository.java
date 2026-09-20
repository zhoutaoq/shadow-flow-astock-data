package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.config.MarketDataProperties;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库模式：配置落 {@code shadowflow_sys_config}，全量载入内存缓存，按「归属用户 + 场景 + 键」索引。
 *
 * <p>页面保存后立即生效；手工改了库点「重新加载」。
 */
@Repository
@DependsOn("shadowFlowDbInitializer")
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbSysConfigRepository implements SysConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DbSysConfigRepository.class);

    private static final String TABLE = "shadowflow_sys_config";

    private static final String COLUMNS = "id, owner_id, config_scene, config_key, config_value, remark,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private final NamedParameterJdbcTemplate named;
    private final MarketDataProperties defaults;

    /**
     * ownerId|scene|key → 值。
     *
     * <p>用 volatile 引用整体替换而不是原地改 HashMap：抓取线程每次请求都会读 token，
     * 原地写非线程安全的 HashMap 会踩并发问题，整体替换则读侧无需加锁。
     */
    private volatile Map<String, String> cache = new HashMap<>();

    public DbSysConfigRepository(NamedParameterJdbcTemplate named, MarketDataProperties defaults) {
        this.named = named;
        this.defaults = defaults;
    }

    @PostConstruct
    public synchronized void init() {
        migrate();
        reload();
        seedToken();
        log.info("[sys-config] 已载入 {} 条配置；系统级 token={}", cache.size(), mask(dataApiToken()));
        if (!StringUtils.hasText(dataApiToken())) {
            // 新库首次启动时这里必须喊一声，否则所有请求都带空 token，
            // 表现为"接口返回异常"，很难定位
            log.warn("[sys-config] ⚠️ 系统级行情数据服务 token 为空！请到「实时通知配置」页填写，"
                    + "否则未单独配置令牌的用户抓取都会失败");
        }
    }

    /**
     * v1.5 表结构迁移：老库补 {@code owner_id} 列并把唯一键换成「归属+场景+键」。
     *
     * <p>幂等 —— 每一步失败都只记 debug 日志继续走：新库由 {@code schema.sql} 建表时
     * 就已经是目标结构，这里的 ALTER 必然报「列/键已存在」，属于正常情况。
     */
    private void migrate() {
        exec("ALTER TABLE " + TABLE + " ADD COLUMN owner_id BIGINT NOT NULL DEFAULT 0"
                + " COMMENT '归属用户 id；0 = 系统级兜底' AFTER id");
        exec("ALTER TABLE " + TABLE + " DROP INDEX uk_shadowflow_sys_config_scene_key");
        exec("ALTER TABLE " + TABLE + " ADD UNIQUE KEY uk_shadowflow_sys_config_owner_scene_key"
                + " (owner_id, config_scene, config_key)");
    }

    private void exec(String sql) {
        try {
            named.getJdbcTemplate().execute(sql);
            log.info("[sys-config] 表结构迁移已执行：{}", sql);
        } catch (Exception e) {
            // 已经迁移过 / 新库本来就是这个结构，都会走到这里
            log.debug("[sys-config] 表结构迁移跳过（{}）：{}", sql, e.getMessage());
        }
    }

    @Override
    public String get(long ownerId, String scene, String key) {
        return cache.get(cacheKey(ownerId, scene, key));
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
    public List<SysConfig> list(long ownerId) {
        return named.query("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE owner_id = :owner"
                        + " ORDER BY config_scene, config_key",
                new MapSqlParameterSource("owner", ownerId), new BeanPropertyRowMapper<>(SysConfig.class));
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
        if (cfg.getConfigValue() == null) {
            cfg.setConfigValue("");
        }
        named.update("INSERT INTO " + TABLE
                        + " (owner_id, config_scene, config_key, config_value, remark) "
                        + "VALUES (:owner, :scene, :key, :value, :remark) "
                        + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value),"
                        + " remark = VALUES(remark)",
                new MapSqlParameterSource()
                        .addValue("owner", cfg.getOwnerId())
                        .addValue("scene", scene)
                        .addValue("key", key)
                        .addValue("value", cfg.getConfigValue().trim())
                        .addValue("remark", cfg.getRemark()));
        reloadCache();
        log.info("[sys-config] 保存 owner={} {}/{} 已生效", cfg.getOwnerId(), scene, key);
    }

    @Override
    public synchronized void delete(long ownerId, long id) {
        // owner_id 一起进 where：避免越权删掉别人的配置行
        int n = named.update("DELETE FROM " + TABLE + " WHERE id = :id AND owner_id = :owner",
                new MapSqlParameterSource().addValue("id", id).addValue("owner", ownerId));
        if (n > 0) {
            reloadCache();
        }
    }

    @Override
    public synchronized void reload() {
        reloadCache();
    }

    private synchronized void reloadCache() {
        Map<String, String> fresh = new HashMap<>();
        for (SysConfig c : named.query("SELECT " + COLUMNS + " FROM " + TABLE,
                new MapSqlParameterSource(), new BeanPropertyRowMapper<>(SysConfig.class))) {
            fresh.put(cacheKey(c.getOwnerId(), c.getConfigScene(), c.getConfigKey()),
                    c.getConfigValue() == null ? "" : c.getConfigValue());
        }
        cache = fresh;
    }

    /**
     * 系统级 token 初次入库：配置表里既没有新场景也没有旧场景的行时，依次尝试
     * ① application.yml 的 shadowflow.api.token；② 更早的独立配置表里的 token。
     */
    private void seedToken() {
        if (StringUtils.hasText(dataApiToken())) {
            return;
        }
        String seed = StringUtils.hasText(defaults.getApi().getToken())
                ? defaults.getApi().getToken().trim()
                : legacyToken();
        if (!StringUtils.hasText(seed)) {
            return;
        }
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SYSTEM_OWNER);
        cfg.setConfigScene(SCENE_DATA_API);
        cfg.setConfigKey(KEY_TOKEN);
        cfg.setConfigValue(seed);
        cfg.setRemark("首次启动自动初始化，请按需更新");
        save(cfg);
        log.info("[sys-config] 已初始化系统级 {}/{}", SCENE_DATA_API, KEY_TOKEN);
    }

    /** 从旧表 waizao_api_config 读 token；表不存在或无数据返回 null */
    private String legacyToken() {
        try {
            List<String> rows = named.queryForList(
                    "SELECT token FROM waizao_api_config WHERE supplier_code = 'waizao'"
                            + " AND token IS NOT NULL AND token <> '' LIMIT 1",
                    new MapSqlParameterSource(), String.class);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (Exception e) {
            // 新环境没有这张旧表，属于正常情况
            log.debug("[sys-config] 未从旧配置表取到 token: {}", e.getMessage());
            return null;
        }
    }

    private static String cacheKey(long ownerId, String scene, String key) {
        return ownerId + "|" + (scene == null ? "" : scene) + "|" + (key == null ? "" : key);
    }

    /** token 打码，避免日志泄漏 */
    static String mask(String token) {
        if (token == null || token.isEmpty()) {
            return "(空)";
        }
        return token.length() <= 8
                ? "****"
                : token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }
}
