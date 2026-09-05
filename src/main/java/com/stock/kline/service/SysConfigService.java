package com.stock.kline.service;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.SysConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用配置表 waizao_sys_config：按「场景 + 键」存放运行期可变的变量。
 *
 * <p>设计边界：只有会频繁变动的凭据/参数放这里（当前是 waizao 的 token）；
 * 接口地址、超时、并发度这些稳定参数走 application.yml，改了重启生效。
 * 全量载入内存缓存，页面保存后立即生效；手工改了数据库点「重新加载」。
 */
@Service
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class SysConfigService {

    private static final Logger log = LoggerFactory.getLogger(SysConfigService.class);

    private static final String TABLE = "waizao_sys_config";

    /** 场景：waizao 供应商（后续其他场景继续加常量即可） */
    public static final String SCENE_WAIZAO = "waizao";
    /** waizao 场景下的接口令牌 */
    public static final String KEY_TOKEN = "token";

    private static final String COLUMNS = "id, config_scene, config_key, config_value, remark,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private final NamedParameterJdbcTemplate named;
    private final WaizaoProperties defaults;

    /**
     * 缓存 scene|key → 值。
     *
     * <p>用 volatile 引用整体替换而不是原地改 HashMap：抓取线程每次请求都会读 token，
     * 原地写非线程安全的 HashMap 会踩并发问题，整体替换则读侧无需加锁。
     */
    private volatile Map<String, String> cache = new HashMap<>();

    public SysConfigService(NamedParameterJdbcTemplate named, WaizaoProperties defaults) {
        this.named = named;
        this.defaults = defaults;
    }

    @PostConstruct
    public synchronized void init() {
        reloadCache();
        seedToken();
        log.info("[sys-config] 已载入 {} 条配置；生效 token={}",
                cache.size(), mask(get(SCENE_WAIZAO, KEY_TOKEN)));
        if (!StringUtils.hasText(get(SCENE_WAIZAO, KEY_TOKEN))) {
            // 新库首次启动时这里必须喊一声，否则所有请求都带空 token，
            // 表现为"接口返回异常"，很难定位
            log.warn("[sys-config] ⚠️ waizao/token 为空！请到「系统配置」页填写，否则所有抓取都会失败");
        }
    }

    /** 取值，没有返回 null */
    public String get(String scene, String key) {
        return cache.get(cacheKey(scene, key));
    }

    /** 取值，没有返回默认值 */
    public String get(String scene, String key, String def) {
        String v = get(scene, key);
        return StringUtils.hasText(v) ? v : def;
    }

    /** waizao 供应商当前生效的 token */
    public String waizaoToken() {
        return get(SCENE_WAIZAO, KEY_TOKEN, "");
    }

    /** 全部配置项（含值，页面自行对敏感项打码） */
    public List<SysConfig> list() {
        return named.query("SELECT " + COLUMNS + " FROM " + TABLE
                        + " ORDER BY config_scene, config_key",
                new MapSqlParameterSource(), new BeanPropertyRowMapper<>(SysConfig.class));
    }

    /**
     * 新增或更新（按 scene + key 定位）。保存后立即生效，无需重启。
     */
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

    /** 删除配置项 */
    public synchronized void delete(long id) {
        int n = named.update("DELETE FROM " + TABLE + " WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (n > 0) {
            reloadCache();
        }
    }

    /** 从数据库重新加载（手工改库后调用） */
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
     *
     * <p>第 ② 条是一次性兼容：token 原先维护在 waizao_api_config，改由通用配置表接管后
     * 把值搬过来，避免用户重新找一遍令牌。旧表不存在（新环境）直接跳过。
     */
    private void seedToken() {
        if (StringUtils.hasText(get(SCENE_WAIZAO, KEY_TOKEN))) {
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
    private static String mask(String token) {
        if (token == null || token.isEmpty()) {
            return "(空)";
        }
        return token.length() <= 8
                ? "****"
                : token.substring(0, 4) + "****" + token.substring(token.length() - 4);
    }

}
