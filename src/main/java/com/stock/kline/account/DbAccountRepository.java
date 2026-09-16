package com.stock.kline.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.math.BigDecimal;
import java.util.List;

/**
 * 数据库模式：账户落 {@code waizao_account}，持仓落 {@code waizao_position}。
 *
 * <p>与 {@link FileAccountRepository} 暴露完全相同的行为，页面无需关心当前是哪种模式。
 * 所有语句都带 {@code user_id} 条件，杜绝越权读写别人的账户与持仓；
 * 账户级操作再带 {@code id} / {@code account_id} 落到具体一本账。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbAccountRepository implements AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(DbAccountRepository.class);

    private static final String T_ACCOUNT = "waizao_account";
    private static final String T_POSITION = "waizao_position";

    private static final String ACCOUNT_COLUMNS = "id, user_id, account_name AS name, init_value, cash, remark,"
            + " day_realized_date, day_realized_profit,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private static final String POSITION_COLUMNS = "id, user_id, account_id, stock_code AS code,"
            + " stock_name AS name, cost_price, quantity, open_date,"
            + " day_buy_date, day_buy_qty, day_buy_amount,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private final NamedParameterJdbcTemplate named;

    public DbAccountRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /**
     * v1.5.2 表结构迁移：把「一人一条」的账户表改成「一人多本账」，持仓挂到 account_id 上。
     *
     * <p>幂等 —— 每一步失败都只记 debug 日志继续走：新库由 {@code schema.sql} 建表时
     * 就已经是目标结构，这里的 ALTER 必然报「列/键已存在」，属于正常情况。
     *
     * <p>老数据的归宿：{@code waizao_account} 每行变成一本账（自动补个自增 id），
     * 老持仓的 {@code account_id} 先落 0，再由最后一条 UPDATE 按 user_id 认领到该用户那本账上 ——
     * 升级完用户看到的还是原来那份数据。
     */
    @PostConstruct
    public void init() {
        migrate();
    }

    private void migrate() {
        exec("ALTER TABLE " + T_ACCOUNT + " ADD COLUMN account_name VARCHAR(64) NOT NULL"
                + " DEFAULT '默认账户' COMMENT '账户名称' AFTER user_id");
        // AUTO_INCREMENT 列必须同时是键，所以先补一个唯一键，再摘掉 user_id 上的主键
        exec("ALTER TABLE " + T_ACCOUNT + " ADD COLUMN id BIGINT NOT NULL AUTO_INCREMENT FIRST,"
                + " ADD UNIQUE KEY uk_waizao_account_id (id)");
        exec("ALTER TABLE " + T_ACCOUNT + " DROP PRIMARY KEY, ADD PRIMARY KEY (id)");
        exec("ALTER TABLE " + T_ACCOUNT + " DROP INDEX uk_waizao_account_id,"
                + " ADD KEY idx_waizao_account_user (user_id)");

        exec("ALTER TABLE " + T_POSITION + " ADD COLUMN account_id BIGINT NOT NULL DEFAULT 0"
                + " COMMENT '归属账户 id' AFTER user_id");
        exec("ALTER TABLE " + T_POSITION + " DROP INDEX uk_waizao_position_user_code,"
                + " ADD UNIQUE KEY uk_waizao_position_account_code (account_id, stock_code),"
                + " ADD KEY idx_waizao_position_account (account_id)");

        // v1.5.5：建仓日（非必填）+ 当天买入池 + 账户当日已实现盈亏
        exec("ALTER TABLE " + T_POSITION + " ADD COLUMN open_date VARCHAR(10) NULL"
                + " COMMENT '建仓日 yyyy-MM-dd（非必填）' AFTER quantity");
        exec("ALTER TABLE " + T_POSITION + " ADD COLUMN day_buy_date VARCHAR(10) NULL"
                + " COMMENT '当天买入池归属日期 yyyy-MM-dd，跨天作废' AFTER open_date");
        exec("ALTER TABLE " + T_POSITION + " ADD COLUMN day_buy_qty INT NOT NULL DEFAULT 0"
                + " COMMENT '当天买入且仍在持仓中的数量（股）' AFTER day_buy_date");
        exec("ALTER TABLE " + T_POSITION + " ADD COLUMN day_buy_amount DECIMAL(20, 4) NOT NULL DEFAULT 0"
                + " COMMENT '当天买入部分的成交金额（元）' AFTER day_buy_qty");
        exec("ALTER TABLE " + T_ACCOUNT + " ADD COLUMN day_realized_date VARCHAR(10) NULL"
                + " COMMENT '当日已实现盈亏归属日期 yyyy-MM-dd' AFTER cash");
        exec("ALTER TABLE " + T_ACCOUNT + " ADD COLUMN day_realized_profit DECIMAL(20, 4) NOT NULL DEFAULT 0"
                + " COMMENT '当日已实现盈亏（元）' AFTER day_realized_date");

        // v1.5.1 允许「只加持仓、没保存过账户设置」，这种用户没有账户行可挂 ——
        // 先给有持仓的用户补一本默认账，再统一认领，数据一条都不会丢
        exec("INSERT INTO " + T_ACCOUNT + " (user_id, account_name, init_value, cash)"
                + " SELECT DISTINCT p.user_id, '默认账户', 0, 0 FROM " + T_POSITION + " p"
                + " LEFT JOIN " + T_ACCOUNT + " a ON a.user_id = p.user_id WHERE a.id IS NULL");
        // 老持仓认领：迁移后每个老用户恰好一本账，把 account_id=0 的行挂过去
        exec("UPDATE " + T_POSITION + " p JOIN " + T_ACCOUNT + " a ON a.user_id = p.user_id"
                + " SET p.account_id = a.id WHERE p.account_id = 0");
        warnOrphans();
    }

    /**
     * 认领不掉的持仓（用户从没建过账户）会一直是 {@code account_id = 0}，
     * 而列表一律按 account_id 取，这些行等于静默失联 —— 有就喊一声，别让它悄悄消失。
     */
    private void warnOrphans() {
        try {
            Integer n = named.queryForObject("SELECT COUNT(*) FROM " + T_POSITION + " WHERE account_id = 0",
                    new MapSqlParameterSource(), Integer.class);
            if (n != null && n > 0) {
                log.warn("[account] {} 条老持仓没有可归属的账户（account_id=0），页面上看不到；"
                        + "需要保留的话请手工把它们的 account_id 指到该用户名下的账户", n);
            }
        } catch (Exception e) {
            log.debug("[account] 孤儿持仓检查跳过：{}", e.getMessage());
        }
    }

    private void exec(String sql) {
        try {
            named.getJdbcTemplate().execute(sql);
            log.info("[account] 表结构迁移已执行：{}", sql);
        } catch (Exception e) {
            // 已经迁移过 / 新库本来就是这个结构，都会走到这里
            log.debug("[account] 表结构迁移跳过（{}）：{}", sql, e.getMessage());
        }
    }

    /* ==================== 账户 ==================== */

    @Override
    public List<Account> listAccounts(long userId) {
        return named.query("SELECT " + ACCOUNT_COLUMNS + " FROM " + T_ACCOUNT
                        + " WHERE user_id = :uid ORDER BY id",
                new MapSqlParameterSource("uid", userId), new BeanPropertyRowMapper<>(Account.class));
    }

    @Override
    public Account findAccount(long userId, long accountId) {
        List<Account> rows = named.query("SELECT " + ACCOUNT_COLUMNS + " FROM " + T_ACCOUNT
                        + " WHERE user_id = :uid AND id = :aid",
                new MapSqlParameterSource().addValue("uid", userId).addValue("aid", accountId),
                new BeanPropertyRowMapper<>(Account.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public Account createAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        KeyHolder keys = new GeneratedKeyHolder();
        named.update("INSERT INTO " + T_ACCOUNT
                        + " (user_id, account_name, init_value, cash, remark,"
                        + " day_realized_date, day_realized_profit)"
                        + " VALUES (:uid, :name, :init, :cash, :remark, :realizedDate, :realized)",
                new MapSqlParameterSource()
                        .addValue("uid", account.getUserId())
                        .addValue("name", account.getName())
                        .addValue("init", nz(account.getInitValue()))
                        .addValue("cash", nz(account.getCash()))
                        .addValue("remark", account.getRemark())
                        .addValue("realizedDate", account.getDayRealizedDate())
                        .addValue("realized", nz(account.getDayRealizedProfit())),
                keys, new String[]{"id"});
        Number key = keys.getKey();
        if (key != null) {
            account.setId(key.longValue());
        }
        log.info("[account] 用户 {} 新建账户 {}（{}）", account.getUserId(), account.getId(), account.getName());
        return account;
    }

    @Override
    public void saveAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", account.getUserId())
                .addValue("name", account.getName())
                .addValue("init", nz(account.getInitValue()))
                .addValue("cash", nz(account.getCash()))
                .addValue("remark", account.getRemark())
                .addValue("realizedDate", account.getDayRealizedDate())
                .addValue("realized", nz(account.getDayRealizedProfit()));
        if (account.getId() > 0) {
            // id + user_id 一起进 where：改不动别人的账户
            int n = named.update("UPDATE " + T_ACCOUNT
                            + " SET account_name = :name, init_value = :init, cash = :cash, remark = :remark,"
                            + " day_realized_date = :realizedDate, day_realized_profit = :realized"
                            + " WHERE id = :id AND user_id = :uid",
                    params.addValue("id", account.getId()));
            if (n > 0) {
                log.info("[account] 用户 {} 账户 {}（{}）已保存：期初市值 {}，可用金额 {}",
                        account.getUserId(), account.getId(), account.getName(),
                        account.getInitValue(), account.getCash());
                return;
            }
        }
        createAccount(account);
    }

    @Override
    public void deleteAccount(long userId, long accountId) {
        named.update("DELETE FROM " + T_POSITION + " WHERE user_id = :uid AND account_id = :aid",
                new MapSqlParameterSource().addValue("uid", userId).addValue("aid", accountId));
        named.update("DELETE FROM " + T_ACCOUNT + " WHERE user_id = :uid AND id = :aid",
                new MapSqlParameterSource().addValue("uid", userId).addValue("aid", accountId));
        log.info("[account] 用户 {} 删除账户 {}（连同其持仓）", userId, accountId);
    }

    /* ==================== 持仓 ==================== */

    @Override
    public List<Position> listPositions(long userId, long accountId) {
        return named.query("SELECT " + POSITION_COLUMNS + " FROM " + T_POSITION
                        + " WHERE user_id = :uid AND account_id = :aid ORDER BY stock_code",
                new MapSqlParameterSource().addValue("uid", userId).addValue("aid", accountId),
                new BeanPropertyRowMapper<>(Position.class));
    }

    @Override
    public Position savePosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("持仓内容不能为空");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", position.getUserId())
                .addValue("aid", position.getAccountId())
                .addValue("code", position.getCode())
                .addValue("name", position.getName())
                .addValue("cost", nz(position.getCostPrice()))
                .addValue("qty", position.getQuantity())
                .addValue("openDate", position.getOpenDate())
                .addValue("dayBuyDate", position.getDayBuyDate())
                .addValue("dayBuyQty", position.getDayBuyQty())
                .addValue("dayBuyAmount", nz(position.getDayBuyAmount()));
        if (position.getId() == null) {
            KeyHolder keys = new GeneratedKeyHolder();
            named.update("INSERT INTO " + T_POSITION
                            + " (user_id, account_id, stock_code, stock_name, cost_price, quantity, open_date,"
                            + " day_buy_date, day_buy_qty, day_buy_amount)"
                            + " VALUES (:uid, :aid, :code, :name, :cost, :qty, :openDate,"
                            + " :dayBuyDate, :dayBuyQty, :dayBuyAmount)",
                    params, keys, new String[]{"id"});
            Number key = keys.getKey();
            if (key != null) {
                position.setId(key.longValue());
            }
        } else {
            // id + user_id + account_id 一起进 where：改不动别人账户里的持仓
            params.addValue("id", position.getId());
            named.update("UPDATE " + T_POSITION
                            + " SET stock_code = :code, stock_name = :name,"
                            + " cost_price = :cost, quantity = :qty, open_date = :openDate,"
                            + " day_buy_date = :dayBuyDate, day_buy_qty = :dayBuyQty,"
                            + " day_buy_amount = :dayBuyAmount"
                            + " WHERE id = :id AND user_id = :uid AND account_id = :aid",
                    params);
        }
        return position;
    }

    @Override
    public void deletePosition(long userId, long accountId, long id) {
        named.update("DELETE FROM " + T_POSITION + " WHERE id = :id AND user_id = :uid AND account_id = :aid",
                new MapSqlParameterSource().addValue("id", id).addValue("uid", userId).addValue("aid", accountId));
    }

    @Override
    public void deleteAllPositions(long userId, long accountId) {
        named.update("DELETE FROM " + T_POSITION + " WHERE user_id = :uid AND account_id = :aid",
                new MapSqlParameterSource().addValue("uid", userId).addValue("aid", accountId));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
