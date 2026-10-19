package io.github.zhoutaoq.shadowflow.biz.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 数据库模式：账户分钟走势落 {@code shadowflow_account_trend}。
 *
 * <p>唯一键 {@code (account_id, trade_date, minute)} 是这类数据的关键：
 * 定时任务与页面补点可能针对同一分钟先后写两次，落成两行会让曲线出现「同分钟两个值」，
 * 前端按分钟索引时后写的赢、前一条变成永远读不到的垃圾 —— 用 upsert 从根上避免。
 *
 * <p>与文件模式暴露完全相同的接口；所有语句都带 {@code user_id} 条件，杜绝读别人的曲线。
 */
@Repository
@DependsOn("shadowFlowDbInitializer")
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbAccountTrendRepository implements AccountTrendRepository {

    private static final Logger log = LoggerFactory.getLogger(DbAccountTrendRepository.class);

    private static final String T_TREND = "shadowflow_account_trend";

    private final NamedParameterJdbcTemplate named;

    public DbAccountTrendRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /** 兜底建表（新库由 schema.sql 建，这里只保证老库升级后也有这张表） */
    @PostConstruct
    public void init() {
        try {
            named.getJdbcOperations().execute("CREATE TABLE IF NOT EXISTS " + T_TREND + " ("
                    + " id BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',"
                    + " user_id BIGINT NOT NULL COMMENT '归属用户 id',"
                    + " account_id BIGINT NOT NULL COMMENT '归属账户 id',"
                    + " trade_date VARCHAR(10) NOT NULL COMMENT '交易日 yyyy-MM-dd',"
                    + " minute VARCHAR(5) NOT NULL COMMENT '分钟 HH:mm',"
                    + " total_value DECIMAL(20, 4) NOT NULL DEFAULT 0 COMMENT '账户总市值（元）',"
                    + " position_value DECIMAL(20, 4) NOT NULL DEFAULT 0 COMMENT '持仓市值（元）',"
                    + " day_profit DECIMAL(20, 4) NOT NULL DEFAULT 0 COMMENT '当日收益（元）',"
                    + " create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',"
                    + " PRIMARY KEY (id),"
                    + " UNIQUE KEY uk_shadowflow_account_trend (account_id, trade_date, minute),"
                    + " KEY idx_shadowflow_account_trend_user (user_id, trade_date)"
                    + ") ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账户分钟走势'");
        } catch (Exception e) {
            // 建表失败只记日志：走势缺了就少一张图，不该让整个账户模块起不来
            log.warn("[account] 走势表初始化失败（走势功能不可用，其余功能正常）：{}", e.getMessage());
        }
    }

    @Override
    public void savePoint(long userId, long accountId, String date, AccountTrendPoint point) {
        if (userId <= 0 || accountId <= 0 || point == null) {
            return;
        }
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId)
                .addValue("date", date)
                .addValue("minute", point.getMinute())
                .addValue("totalValue", point.getTotalValue())
                .addValue("positionValue", point.getPositionValue())
                .addValue("dayProfit", point.getDayProfit());
        named.update("INSERT INTO " + T_TREND
                + " (user_id, account_id, trade_date, minute, total_value, position_value, day_profit)"
                + " VALUES (:userId, :accountId, :date, :minute, :totalValue, :positionValue, :dayProfit)"
                + " ON DUPLICATE KEY UPDATE total_value = VALUES(total_value),"
                + " position_value = VALUES(position_value), day_profit = VALUES(day_profit)", p);
    }

    @Override
    public List<AccountTrendPoint> list(long userId, long accountId, String date) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("accountId", accountId)
                .addValue("date", date);
        return named.query("SELECT minute AS minute, total_value AS totalValue,"
                        + " position_value AS positionValue, day_profit AS dayProfit"
                        + " FROM " + T_TREND
                        + " WHERE user_id = :userId AND account_id = :accountId AND trade_date = :date"
                        + " ORDER BY minute",
                p, (rs, i) -> {
                    AccountTrendPoint point = new AccountTrendPoint();
                    point.setMinute(rs.getString("minute"));
                    point.setTotalValue(rs.getBigDecimal("totalValue"));
                    point.setPositionValue(rs.getBigDecimal("positionValue"));
                    point.setDayProfit(rs.getBigDecimal("dayProfit"));
                    return point;
                });
    }

    @Override
    public int purgeBefore(String date) {
        return named.update("DELETE FROM " + T_TREND + " WHERE trade_date < :date",
                new MapSqlParameterSource("date", date));
    }

    @Override
    public void deleteAccount(long userId, long accountId) {
        named.update("DELETE FROM " + T_TREND + " WHERE user_id = :userId AND account_id = :accountId",
                new MapSqlParameterSource().addValue("userId", userId).addValue("accountId", accountId));
    }
}
