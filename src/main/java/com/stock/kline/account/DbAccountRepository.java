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

import java.math.BigDecimal;
import java.util.List;

/**
 * 数据库模式：账户落 {@code waizao_account}，持仓落 {@code waizao_position}。
 *
 * <p>与 {@link FileAccountRepository} 暴露完全相同的行为，页面无需关心当前是哪种模式。
 * 所有语句都带 {@code user_id} 条件，杜绝越权读写别人的账户与持仓。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbAccountRepository implements AccountRepository {

    private static final Logger log = LoggerFactory.getLogger(DbAccountRepository.class);

    private static final String T_ACCOUNT = "waizao_account";
    private static final String T_POSITION = "waizao_position";

    private static final String ACCOUNT_COLUMNS = "user_id, init_value, cash, remark,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private static final String POSITION_COLUMNS = "id, user_id, stock_code AS code, stock_name AS name,"
            + " cost_price, quantity,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private final NamedParameterJdbcTemplate named;

    public DbAccountRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public Account findAccount(long userId) {
        List<Account> rows = named.query("SELECT " + ACCOUNT_COLUMNS + " FROM " + T_ACCOUNT
                        + " WHERE user_id = :uid",
                new MapSqlParameterSource("uid", userId), new BeanPropertyRowMapper<>(Account.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public void saveAccount(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        named.update("INSERT INTO " + T_ACCOUNT
                        + " (user_id, init_value, cash, remark) VALUES (:uid, :init, :cash, :remark)"
                        + " ON DUPLICATE KEY UPDATE init_value = VALUES(init_value),"
                        + " cash = VALUES(cash), remark = VALUES(remark)",
                new MapSqlParameterSource()
                        .addValue("uid", account.getUserId())
                        .addValue("init", nz(account.getInitValue()))
                        .addValue("cash", nz(account.getCash()))
                        .addValue("remark", account.getRemark()));
        log.info("[account] 用户 {} 账户已保存：期初市值 {}，可用金额 {}",
                account.getUserId(), account.getInitValue(), account.getCash());
    }

    @Override
    public List<Position> listPositions(long userId) {
        return named.query("SELECT " + POSITION_COLUMNS + " FROM " + T_POSITION
                        + " WHERE user_id = :uid ORDER BY stock_code",
                new MapSqlParameterSource("uid", userId), new BeanPropertyRowMapper<>(Position.class));
    }

    @Override
    public Position savePosition(Position position) {
        if (position == null) {
            throw new IllegalArgumentException("持仓内容不能为空");
        }
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("uid", position.getUserId())
                .addValue("code", position.getCode())
                .addValue("name", position.getName())
                .addValue("cost", nz(position.getCostPrice()))
                .addValue("qty", position.getQuantity());
        if (position.getId() == null) {
            KeyHolder keys = new GeneratedKeyHolder();
            named.update("INSERT INTO " + T_POSITION
                            + " (user_id, stock_code, stock_name, cost_price, quantity)"
                            + " VALUES (:uid, :code, :name, :cost, :qty)",
                    params, keys, new String[]{"id"});
            Number key = keys.getKey();
            if (key != null) {
                position.setId(key.longValue());
            }
        } else {
            // id + user_id 一起进 where：改不动别人的持仓
            params.addValue("id", position.getId());
            named.update("UPDATE " + T_POSITION
                            + " SET stock_code = :code, stock_name = :name,"
                            + " cost_price = :cost, quantity = :qty"
                            + " WHERE id = :id AND user_id = :uid",
                    params);
        }
        return position;
    }

    @Override
    public void deletePosition(long userId, long id) {
        named.update("DELETE FROM " + T_POSITION + " WHERE id = :id AND user_id = :uid",
                new MapSqlParameterSource().addValue("id", id).addValue("uid", userId));
    }

    @Override
    public void deleteAllPositions(long userId) {
        named.update("DELETE FROM " + T_POSITION + " WHERE user_id = :uid",
                new MapSqlParameterSource("uid", userId));
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
