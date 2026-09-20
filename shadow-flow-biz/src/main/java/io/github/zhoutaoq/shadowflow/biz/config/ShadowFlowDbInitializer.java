package io.github.zhoutaoq.shadowflow.biz.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据库模式的启动钩子（v1.6.0）：<b>先把老库改造成新命名，再建表</b>。
 *
 * <p><b>为什么必须由这里接管建表</b>：本版把表前缀由 {@code waizao_} 改成 {@code shadowflow_}。
 * 若仍然交给 {@code spring.sql.init} 跑 {@code schema.sql}，执行顺序就变成
 * 「先 CREATE TABLE IF NOT EXISTS shadowflow_sys_config（建出一张空表）→ 再 RENAME 老表」，
 * 而目标表已存在时 {@code RENAME TABLE} 会直接失败 —— 结果是老数据留在旧表里没人读，
 * 表面上却一切正常。所以本类显式规定顺序，并把 {@code spring.sql.init.mode} 关掉。
 *
 * <p>三步都是幂等的，重复启动不会重复生效：
 * <ol>
 *   <li>{@link #renameLegacyTables} —— 旧表存在且新表不存在时才 {@code RENAME TABLE}；</li>
 *   <li>{@link #applySchema} —— 跑 {@code schema.sql}，语句本身全是 {@code IF NOT EXISTS}；</li>
 *   <li>{@link #rewriteLegacySceneKeys} —— 配置行的场景值 {@code waizao} → {@code shadowflow}，
 *       同 owner + key 已有新场景行时直接丢弃旧行（新行优先，与读取兜底口径一致）。</li>
 * </ol>
 *
 * <p>顺序保证：所有走数据库的仓储（{@code Db*}）都标了 {@code @DependsOn("shadowFlowDbInitializer")}，
 * 它们的 {@code @PostConstruct} 取数一定发生在本类跑完之后。
 *
 * <p>无数据库模式（默认）下本类不装配 —— 那条路径的数据落在 JSON 文件里，
 * 由 {@code FileSysConfigRepository} 自己完成场景值改写。
 */
@Component
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class ShadowFlowDbInitializer implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ShadowFlowDbInitializer.class);

    /** v1.6.0 之前的旧表名 → 新表名。顺序无关，逐个判断存在性。 */
    private static final Map<String, String> LEGACY_TABLES = legacyTables();

    /** 通用配置表（场景值改写落在这张表上） */
    private static final String SYS_CONFIG = "shadowflow_sys_config";

    private final DataSource dataSource;

    public ShadowFlowDbInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void afterPropertiesSet() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        renameLegacyTables(jdbc);
        applySchema(jdbc);
        rewriteLegacySceneKeys(jdbc);
    }

    /** waizao_user 等老表改名为 shadowflow_user 等；表不存在则什么都不做 */
    private void renameLegacyTables(JdbcTemplate jdbc) {
        for (Map.Entry<String, String> e : LEGACY_TABLES.entrySet()) {
            String legacy = e.getKey();
            String current = e.getValue();
            if (!tableExists(jdbc, legacy)) {
                continue;
            }
            if (tableExists(jdbc, current)) {
                // schema.sql 先跑过、或有人手工建过新表，这时 RENAME 会失败。
                // 数据在哪张表上说不清，不做任何猜测，只把它喊出来让人工确认。
                log.warn("[db-migration] 旧表 {} 与新表 {} 同时存在，未自动改名 ——"
                        + "请人工确认数据在哪张表上后合并，合并完删掉旧表即可", legacy, current);
                continue;
            }
            jdbc.execute("RENAME TABLE " + legacy + " TO " + current);
            log.info("[db-migration] 旧表已改名：{} → {}", legacy, current);
        }
    }

    /** 建表：schema.sql 里全是 CREATE TABLE IF NOT EXISTS，重复跑无副作用 */
    private void applySchema(JdbcTemplate jdbc) {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        populator.addScript(new ClassPathResource("schema.sql"));
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    /**
     * 配置行的场景值改名。
     *
     * <p>先删重复行再统一改：{@code (owner_id, config_scene, config_key)} 上有唯一键，
     * 若同一 owner + key 已经有一行新场景的（页面改过、或升级后新写入的），
     * 直接改旧行会撞唯一键。口径与读取侧一致 —— 新场景值优先，旧行丢弃。
     */
    private void rewriteLegacySceneKeys(JdbcTemplate jdbc) {
        if (!tableExists(jdbc, SYS_CONFIG)) {
            return;
        }
        int dropped;
        try {
            dropped = jdbc.update("DELETE l FROM " + SYS_CONFIG + " l JOIN " + SYS_CONFIG + " n"
                            + " ON n.owner_id = l.owner_id AND n.config_key = l.config_key"
                            + " AND n.config_scene = 'shadowflow'"
                            + " WHERE l.config_scene = 'waizao'");
        } catch (Exception ex) {
            // 唯一键不存在时（极老的结构）上面的自连接删不掉重复，退化成只做 UPDATE 也能跑通
            log.debug("[db-migration] 去重删除跳过：{}", ex.getMessage());
            dropped = 0;
        }
        int changed = jdbc.update("UPDATE " + SYS_CONFIG
                + " SET config_scene = 'shadowflow' WHERE config_scene = 'waizao'");
        if (changed > 0 || dropped > 0) {
            log.info("[db-migration] 系统配置行场景值 waizao → shadowflow：改写 {} 行，去重删除 {} 行",
                    changed, dropped);
        }
    }

    private boolean tableExists(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables"
                + " WHERE table_schema = DATABASE() AND table_name = ?", Integer.class, table);
        return n != null && n > 0;
    }

    private static Map<String, String> legacyTables() {
        Map<String, String> m = new LinkedHashMap<>();
        // 长的在前只是为了易读，取值靠 get，与顺序无关
        m.put("waizao_sys_config", "shadowflow_sys_config");
        m.put("waizao_stock_list", "shadowflow_stock_list");
        m.put("waizao_stock_kline_daily", "shadowflow_stock_kline_daily");
        m.put("waizao_api_call_log", "shadowflow_api_call_log");
        m.put("waizao_user", "shadowflow_user");
        m.put("waizao_account", "shadowflow_account");
        m.put("waizao_position", "shadowflow_position");
        m.put("waizao_trade_record", "shadowflow_trade_record");
        m.put("waizao_backtest", "shadowflow_backtest");
        m.put("waizao_backtest_daily", "shadowflow_backtest_daily");
        m.put("waizao_backtest_trade", "shadowflow_backtest_trade");
        return m;
    }
}
