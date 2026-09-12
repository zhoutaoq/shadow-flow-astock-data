package com.stock.kline.repo;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.SysConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据库模式：配置落 {@code waizao_sys_config}，全量载入内存缓存。
 *
 * <p>页面保存后立即生效；手工改了库点「重新加载」。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbSysConfigRepository implements SysConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DbSysConfigRepository.class);

    private static final String TABLE = "waizao_sys_config";

    private static final String COLUMNS = "id, config_scene, config_key, config_value, remark,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private final NamedParameterJdbcTemplate named;
    private final WaizaoProperties defaults;

    /**
     * scene|key → 值。
     *
     * <p>用 volatile 引用整体替换而不是原地改 HashMap：抓取线程每次请求都会读 token，
     * 原地写非线程安全的 HashMap 会踩并发问题，整体替换则读侧无需加锁。
     */
    private volatile Map<String, String> cache = new HashMap<>();

    public DbSysConfigRepository(NamedParameterJdbcTemplate named, WaizaoProperties defaults) {
        this.named = named;
        this.defaults = defaults;
    }

    @PostConstruct
    public synchronized void init() {
        reload();
        seedToken();
        log.info("[sys-config] 已载入 {} 条配置；生效 token={}", cache.size(), mask(waizaoToken()));
        if (!StringUtils.hasText(waizaoToken())) {
            // 新库首次启动时这里必须喊一声，否则所有请求都带空 token，
            // 表现为"接口返回异常"，很难定位
            log.warn("[sys-config] ⚠️ waizao/token 为空！请到「系统配置」页填写，否则所有抓取都会失败");
        }
    }

    @Override
    public String get(String scene, String key) {
        return cache.get(cacheKey(scene, key));
    }

    @Override
    public String get(String scene, String key, String def) {
        String v = get(scene, key);
        return StringUtils.hasText(v) ? v : def;
    }

    @Override
    public String waizaoToken() {
        return get(SCENE_WAIZAO, KEY_TOKEN, "");
    }

    @Override
    public List<SysConfig> list() {
        return named.query("SELECT " + COLUMNS + " FROM " + TABLE + " ORDER BY config_scene, config_key",
                new MapSqlParameterSource(), new BeanPropertyRowMapper<>(SysConfig.class));
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
                        + " (config_scene, config_key, config_value, remark) "
                        + "VALUES (:scene, :key, :value, :remark) "
                        + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value),"
                        + " remark = VALUES(remark)",
                new MapSqlParameterSource()
                        .addValue("scene", scene)
                        .addValue("key", key)
                        .addValue("value", cfg.getConfigValue().trim())
                        .addValue("remark", cfg.getRemark()));
        reloadCache();
        log.info("[sys-config] 保存 {}/{} 已生效", scene, key);
    }

    @Override
    public synchronized void delete(long id) {
        int n = named.update("DELETE FROM " + TABLE + " WHERE id = :id",
                new MapSqlParameterSource("id", id));
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
        for (SysConfig c : list()) {
            fresh.put(cacheKey(c.getConfigScene(), c.getConfigKey()),
                    c.getConfigValue() == null ? "" : c.getConfigValue());
        }
        cache = fresh;
    }

    /**
     * token 初次入库：配置表里没有 waizao/token 时，依次尝试
     * ① application.yml 的 waizao.api.token；② 旧供应商配置表里的 token。
     */
    private void seedToken() {
        if (StringUtils.hasText(waizaoToken())) {
            return;
        }
        String seed = StringUtils.hasText(defaults.getApi().getToken())
                ? defaults.getApi().getToken().trim()
                : legacyToken();
        if (!StringUtils.hasText(seed)) {
            return;
        }
        SysConfig cfg = new SysConfig();
        cfg.setConfigScene(SCENE_WAIZAO);
        cfg.setConfigKey(KEY_TOKEN);
        cfg.setConfigValue(seed);
        cfg.setRemark("首次启动自动初始化，请按需更新");
        save(cfg);
        log.info("[sys-config] 已初始化 {}/{}", SCENE_WAIZAO, KEY_TOKEN);
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

    private static String cacheKey(String scene, String key) {
        return (scene == null ? "" : scene) + "|" + (key == null ? "" : key);
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
