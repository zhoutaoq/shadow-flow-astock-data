package io.github.zhoutaoq.shadowflow.biz.quant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 数据库模式：五张 {@code shadowflow_quant_*} 表。
 *
 * <p>与 {@link FileQuantRepository} 行为完全一致，页面不感知当前是哪种模式。
 *
 * <p><b>唯一键就是业务主键</b>：持仓靠 {@code (strategy_id, stock_code)}、
 * 日快照靠 {@code (strategy_id, trade_date)}、分时靠 {@code (strategy_id, trade_date, point_time)}。
 * 所以写入统一走「先 UPDATE，影响 0 行再 INSERT」，避免用 REPLACE 把自增 id 换掉
 * （外键没有，但 id 被换掉会让前端拿着的旧 id 失效）。
 */
@Repository
@DependsOn("shadowFlowDbInitializer")
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbQuantRepository implements QuantRepository {

    private static final Logger log = LoggerFactory.getLogger(DbQuantRepository.class);

    private static final String T_STRATEGY = "shadowflow_quant_strategy";
    private static final String T_HOLDING = "shadowflow_quant_holding";
    private static final String T_TRADE = "shadowflow_quant_trade";
    private static final String T_DAILY = "shadowflow_quant_daily";
    private static final String T_INTRADAY = "shadowflow_quant_intraday";

    private static final String STRATEGY_COLUMNS = "id, user_id, strategy_name AS name, remark, enabled,"
            + " init_capital, cash, watch_codes, min_score, max_holdings, max_position_pct,"
            + " stop_loss_pct, take_profit_pct, trail_drawdown_pct, decision_interval_sec, pending_json,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private static final String HOLDING_COLUMNS = "id, strategy_id, user_id, stock_code AS code,"
            + " stock_name AS name, quantity, cost_price, available_qty, open_date, last_buy_date, peak_price,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time,"
            + " DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') AS update_time";

    private static final String TRADE_COLUMNS = "id, strategy_id, user_id, action, stock_code AS code,"
            + " stock_name AS name, price, quantity, turnover, fee, realized_pnl, cash_after, score, reason,"
            + " trade_date, DATE_FORMAT(trade_time, '%Y-%m-%d %H:%i:%s') AS time";

    private static final String DAILY_COLUMNS = "id, strategy_id, user_id, trade_date, cash, market_value,"
            + " total_asset, day_pnl, day_pnl_pct, cum_pnl, cum_pnl_pct, holding_count,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time";

    private static final String INTRADAY_COLUMNS = "id, strategy_id, user_id, trade_date, point_time AS time,"
            + " cash, market_value, total_asset, day_pnl_pct, holdings_json";

    private final NamedParameterJdbcTemplate named;

    public DbQuantRepository(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    /* ==================== 策略 ==================== */

    @Override
    public List<Long> listStrategyUserIds() {
        return named.queryForList("SELECT DISTINCT user_id FROM " + T_STRATEGY + " ORDER BY user_id",
                new MapSqlParameterSource(), Long.class);
    }

    @Override
    public List<QuantStrategy> listStrategies(long userId) {
        return named.query("SELECT " + STRATEGY_COLUMNS + " FROM " + T_STRATEGY
                        + " WHERE user_id = :userId ORDER BY id",
                new MapSqlParameterSource("userId", userId),
                new BeanPropertyRowMapper<>(QuantStrategy.class));
    }

    @Override
    public List<QuantStrategy> listEnabledStrategies() {
        return named.query("SELECT " + STRATEGY_COLUMNS + " FROM " + T_STRATEGY
                        + " WHERE enabled = 1 ORDER BY id",
                new MapSqlParameterSource(),
                new BeanPropertyRowMapper<>(QuantStrategy.class));
    }

    @Override
    public QuantStrategy findStrategy(long userId, long id) {
        List<QuantStrategy> rows = named.query("SELECT " + STRATEGY_COLUMNS + " FROM " + T_STRATEGY
                        + " WHERE user_id = :userId AND id = :id",
                new MapSqlParameterSource("userId", userId).addValue("id", id),
                new BeanPropertyRowMapper<>(QuantStrategy.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public QuantStrategy createStrategy(QuantStrategy strategy) {
        String sql = "INSERT INTO " + T_STRATEGY
                + " (user_id, strategy_name, remark, enabled, init_capital, cash, watch_codes, min_score,"
                + " max_holdings, max_position_pct, stop_loss_pct, take_profit_pct, trail_drawdown_pct,"
                + " decision_interval_sec, pending_json)"
                + " VALUES (:userId, :name, :remark, :enabled, :initCapital, :cash, :watchCodes, :minScore,"
                + " :maxHoldings, :maxPositionPct, :stopLossPct, :takeProfitPct, :trailDrawdownPct,"
                + " :decisionIntervalSec, :pendingJson)";
        KeyHolder keys = new GeneratedKeyHolder();
        named.update(sql, strategyParams(strategy), keys);
        Number key = keys.getKey();
        if (key != null) {
            strategy.setId(key.longValue());
        }
        log.info("[quant] 用户 {} 新建策略 {}（{}），初始资金 {}",
                strategy.getUserId(), strategy.getId(), strategy.getName(), strategy.getInitCapital());
        return findStrategy(strategy.getUserId(), strategy.getId());
    }

    @Override
    public void saveStrategy(QuantStrategy strategy) {
        String sql = "UPDATE " + T_STRATEGY + " SET strategy_name = :name, remark = :remark,"
                + " enabled = :enabled, init_capital = :initCapital, cash = :cash, watch_codes = :watchCodes,"
                + " min_score = :minScore, max_holdings = :maxHoldings, max_position_pct = :maxPositionPct,"
                + " stop_loss_pct = :stopLossPct, take_profit_pct = :takeProfitPct,"
                + " trail_drawdown_pct = :trailDrawdownPct, decision_interval_sec = :decisionIntervalSec,"
                + " pending_json = :pendingJson"
                + " WHERE id = :id AND user_id = :userId";
        MapSqlParameterSource p = strategyParams(strategy).addValue("id", strategy.getId());
        if (named.update(sql, p) == 0) {
            createStrategy(strategy);
        }
    }

    private MapSqlParameterSource strategyParams(QuantStrategy s) {
        return new MapSqlParameterSource()
                .addValue("userId", s.getUserId())
                .addValue("name", s.getName())
                .addValue("remark", s.getRemark())
                .addValue("enabled", s.isEnabled() ? 1 : 0)
                .addValue("initCapital", s.getInitCapital())
                .addValue("cash", s.getCash())
                .addValue("watchCodes", s.getWatchCodes())
                .addValue("minScore", s.getMinScore())
                .addValue("maxHoldings", s.getMaxHoldings())
                .addValue("maxPositionPct", s.getMaxPositionPct())
                .addValue("stopLossPct", s.getStopLossPct())
                .addValue("takeProfitPct", s.getTakeProfitPct())
                .addValue("trailDrawdownPct", s.getTrailDrawdownPct())
                .addValue("decisionIntervalSec", s.getDecisionIntervalSec())
                .addValue("pendingJson", s.getPendingJson());
    }

    @Override
    public void deleteStrategy(long userId, long id) {
        MapSqlParameterSource p = new MapSqlParameterSource("userId", userId).addValue("id", id);
        // 子表按 strategy_id 删（已校验过归属），主表最后删
        named.update("DELETE FROM " + T_HOLDING + " WHERE strategy_id = :id", p);
        named.update("DELETE FROM " + T_TRADE + " WHERE strategy_id = :id", p);
        named.update("DELETE FROM " + T_DAILY + " WHERE strategy_id = :id", p);
        named.update("DELETE FROM " + T_INTRADAY + " WHERE strategy_id = :id", p);
        named.update("DELETE FROM " + T_STRATEGY + " WHERE id = :id AND user_id = :userId", p);
        log.info("[quant] 用户 {} 删除策略 {}（连同持仓、成交、日快照、分时）", userId, id);
    }

    /* ==================== 持仓 ==================== */

    @Override
    public List<QuantHolding> listHoldings(long strategyId) {
        return named.query("SELECT " + HOLDING_COLUMNS + " FROM " + T_HOLDING
                        + " WHERE strategy_id = :sid ORDER BY stock_code",
                new MapSqlParameterSource("sid", strategyId),
                new BeanPropertyRowMapper<>(QuantHolding.class));
    }

    @Override
    public QuantHolding saveHolding(QuantHolding h) {
        String update = "UPDATE " + T_HOLDING + " SET quantity = :quantity, cost_price = :costPrice,"
                + " available_qty = :availableQty, open_date = :openDate, last_buy_date = :lastBuyDate,"
                + " peak_price = :peakPrice, stock_name = :name"
                + " WHERE strategy_id = :strategyId AND stock_code = :code";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("strategyId", h.getStrategyId())
                .addValue("userId", h.getUserId())
                .addValue("code", h.getCode())
                .addValue("name", h.getName())
                .addValue("quantity", h.getQuantity())
                .addValue("costPrice", h.getCostPrice())
                .addValue("availableQty", h.getAvailableQty())
                .addValue("openDate", h.getOpenDate())
                .addValue("lastBuyDate", h.getLastBuyDate())
                .addValue("peakPrice", h.getPeakPrice());
        if (named.update(update, p) == 0) {
            String insert = "INSERT INTO " + T_HOLDING
                    + " (strategy_id, user_id, stock_code, stock_name, quantity, cost_price, available_qty,"
                    + " open_date, last_buy_date, peak_price)"
                    + " VALUES (:strategyId, :userId, :code, :name, :quantity, :costPrice, :availableQty,"
                    + " :openDate, :lastBuyDate, :peakPrice)";
            KeyHolder keys = new GeneratedKeyHolder();
            named.update(insert, p, keys);
            Number key = keys.getKey();
            if (key != null) {
                h.setId(key.longValue());
            }
        }
        return h;
    }

    @Override
    public void deleteHolding(long strategyId, long id) {
        named.update("DELETE FROM " + T_HOLDING + " WHERE strategy_id = :sid AND id = :id",
                new MapSqlParameterSource("sid", strategyId).addValue("id", id));
    }

    /* ==================== 成交 ==================== */

    @Override
    public List<QuantTrade> listTrades(long strategyId, int limit) {
        String sql = "SELECT " + TRADE_COLUMNS + " FROM " + T_TRADE
                + " WHERE strategy_id = :sid ORDER BY id DESC" + (limit > 0 ? " LIMIT " + limit : "");
        return named.query(sql, new MapSqlParameterSource("sid", strategyId),
                new BeanPropertyRowMapper<>(QuantTrade.class));
    }

    @Override
    public QuantTrade saveTrade(QuantTrade t) {
        String sql = "INSERT INTO " + T_TRADE
                + " (strategy_id, user_id, action, stock_code, stock_name, price, quantity, turnover, fee,"
                + " realized_pnl, cash_after, score, reason, trade_date, trade_time)"
                + " VALUES (:strategyId, :userId, :action, :code, :name, :price, :quantity, :turnover, :fee,"
                + " :realizedPnl, :cashAfter, :score, :reason, :tradeDate, :time)";
        KeyHolder keys = new GeneratedKeyHolder();
        named.update(sql, new MapSqlParameterSource()
                .addValue("strategyId", t.getStrategyId())
                .addValue("userId", t.getUserId())
                .addValue("action", t.getAction())
                .addValue("code", t.getCode())
                .addValue("name", t.getName())
                .addValue("price", t.getPrice())
                .addValue("quantity", t.getQuantity())
                .addValue("turnover", t.getTurnover())
                .addValue("fee", t.getFee())
                .addValue("realizedPnl", t.getRealizedPnl())
                .addValue("cashAfter", t.getCashAfter())
                .addValue("score", t.getScore())
                .addValue("reason", t.getReason())
                .addValue("tradeDate", t.getTradeDate())
                .addValue("time", t.getTime()), keys);
        Number key = keys.getKey();
        if (key != null) {
            t.setId(key.longValue());
        }
        return t;
    }

    /* ==================== 每日快照 ==================== */

    @Override
    public QuantDaily findDaily(long strategyId, String tradeDate) {
        List<QuantDaily> rows = named.query("SELECT " + DAILY_COLUMNS + " FROM " + T_DAILY
                        + " WHERE strategy_id = :sid AND trade_date = :date",
                new MapSqlParameterSource("sid", strategyId).addValue("date", tradeDate),
                new BeanPropertyRowMapper<>(QuantDaily.class));
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public QuantDaily saveDaily(QuantDaily d) {
        String update = "UPDATE " + T_DAILY + " SET cash = :cash, market_value = :marketValue,"
                + " total_asset = :totalAsset, day_pnl = :dayPnl, day_pnl_pct = :dayPnlPct,"
                + " cum_pnl = :cumPnl, cum_pnl_pct = :cumPnlPct, holding_count = :holdingCount"
                + " WHERE strategy_id = :strategyId AND trade_date = :tradeDate";
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("strategyId", d.getStrategyId())
                .addValue("userId", d.getUserId())
                .addValue("tradeDate", d.getTradeDate())
                .addValue("cash", d.getCash())
                .addValue("marketValue", d.getMarketValue())
                .addValue("totalAsset", d.getTotalAsset())
                .addValue("dayPnl", d.getDayPnl())
                .addValue("dayPnlPct", d.getDayPnlPct())
                .addValue("cumPnl", d.getCumPnl())
                .addValue("cumPnlPct", d.getCumPnlPct())
                .addValue("holdingCount", d.getHoldingCount());
        if (named.update(update, p) == 0) {
            String insert = "INSERT INTO " + T_DAILY
                    + " (strategy_id, user_id, trade_date, cash, market_value, total_asset, day_pnl,"
                    + " day_pnl_pct, cum_pnl, cum_pnl_pct, holding_count)"
                    + " VALUES (:strategyId, :userId, :tradeDate, :cash, :marketValue, :totalAsset, :dayPnl,"
                    + " :dayPnlPct, :cumPnl, :cumPnlPct, :holdingCount)";
            KeyHolder keys = new GeneratedKeyHolder();
            named.update(insert, p, keys);
            Number key = keys.getKey();
            if (key != null) {
                d.setId(key.longValue());
            }
        }
        return d;
    }

    @Override
    public List<QuantDaily> listDailies(long strategyId, String from, String to) {
        String sql = "SELECT " + DAILY_COLUMNS + " FROM " + T_DAILY + " WHERE strategy_id = :sid"
                + (from != null && !from.isEmpty() ? " AND trade_date >= :from" : "")
                + (to != null && !to.isEmpty() ? " AND trade_date <= :to" : "")
                + " ORDER BY trade_date";
        MapSqlParameterSource p = new MapSqlParameterSource("sid", strategyId)
                .addValue("from", from)
                .addValue("to", to);
        return named.query(sql, p, new BeanPropertyRowMapper<>(QuantDaily.class));
    }

    /* ==================== 日内分时 ==================== */

    @Override
    public void saveIntraday(QuantIntradayPoint point) {
        String sql = "INSERT INTO " + T_INTRADAY
                + " (strategy_id, user_id, trade_date, point_time, cash, market_value, total_asset,"
                + " day_pnl_pct, holdings_json)"
                + " VALUES (:strategyId, :userId, :tradeDate, :time, :cash, :marketValue, :totalAsset,"
                + " :dayPnlPct, :holdingsJson)"
                + " ON DUPLICATE KEY UPDATE cash = VALUES(cash), market_value = VALUES(market_value),"
                + " total_asset = VALUES(total_asset), day_pnl_pct = VALUES(day_pnl_pct),"
                + " holdings_json = VALUES(holdings_json)";
        named.update(sql, new MapSqlParameterSource()
                .addValue("strategyId", point.getStrategyId())
                .addValue("userId", point.getUserId())
                .addValue("tradeDate", point.getTradeDate())
                .addValue("time", point.getTime())
                .addValue("cash", point.getCash())
                .addValue("marketValue", point.getMarketValue())
                .addValue("totalAsset", point.getTotalAsset())
                .addValue("dayPnlPct", point.getDayPnlPct())
                .addValue("holdingsJson", point.getHoldingsJson()));
    }

    @Override
    public List<QuantIntradayPoint> listIntraday(long strategyId, String tradeDate) {
        return named.query("SELECT " + INTRADAY_COLUMNS + " FROM " + T_INTRADAY
                        + " WHERE strategy_id = :sid AND trade_date = :date ORDER BY point_time",
                new MapSqlParameterSource("sid", strategyId).addValue("date", tradeDate),
                new BeanPropertyRowMapper<>(QuantIntradayPoint.class));
    }

    @Override
    public void purgeIntradayBefore(long strategyId, String tradeDate) {
        named.update("DELETE FROM " + T_INTRADAY
                        + " WHERE strategy_id = :sid AND trade_date < :date",
                new MapSqlParameterSource("sid", strategyId).addValue("date", tradeDate));
    }
}
