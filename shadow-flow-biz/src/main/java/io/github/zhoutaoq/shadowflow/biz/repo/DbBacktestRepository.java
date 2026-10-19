package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.Backtest;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestDaily;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestResult;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestTrade;
import io.github.zhoutaoq.shadowflow.biz.model.StockKlineDaily;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.List;

/**
 * 数据库模式：回测落 shadowflow_backtest / _daily / _trade 三张表。
 *
 * <p>v1.6.8 起主表带 {@code user_id}，列表 / 回看 / 删除一律按登录用户过滤（需求：回测不要跨用户展示）。
 * 老库由 {@link #init()} 幂等补列；老记录 {@code user_id = 0}，只对超管可见，规则见 {@link BacktestOwnership}。
 */
@Repository
@DependsOn("shadowFlowDbInitializer")
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbBacktestRepository implements BacktestRepository {

    private static final Logger log = LoggerFactory.getLogger(DbBacktestRepository.class);

    private static final String BT = "shadowflow_backtest";
    private static final String BT_DAILY = "shadowflow_backtest_daily";
    private static final String BT_TRADE = "shadowflow_backtest_trade";

    private static final String BT_COLUMNS = "id, user_id, stock_code, market,"
            + " DATE_FORMAT(start_date, '%Y-%m-%d') AS start_date,"
            + " DATE_FORMAT(end_date, '%Y-%m-%d') AS end_date,"
            + " init_amount, fee_rate, buy_rule, sell_rule, final_amount, total_profit,"
            + " profit_ratio, trade_count, trade_days,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time";

    private final NamedParameterJdbcTemplate named;
    private final KlineRepository klineRepository;
    private final BacktestOwnership ownership;

    public DbBacktestRepository(NamedParameterJdbcTemplate named, KlineRepository klineRepository,
                                BacktestOwnership ownership) {
        this.named = named;
        this.klineRepository = klineRepository;
        this.ownership = ownership;
    }

    /**
     * 老库补列：{@code user_id} 缺失时加上并建索引。
     *
     * <p>幂等 —— 新库由 schema.sql 建表时就带着这一列，这里的 ALTER 必然报「列已存在」，
     * 属于正常情况，只记 debug。列默认 0，正好就是「本字段引入之前的老记录」的语义。
     */
    @PostConstruct
    public void init() {
        exec("ALTER TABLE " + BT + " ADD COLUMN user_id BIGINT NOT NULL DEFAULT 0"
                + " COMMENT '归属用户 id（0 = v1.6.8 之前的老记录，仅超管可见）' AFTER id");
        exec("ALTER TABLE " + BT + " ADD KEY idx_shadowflow_backtest_user (user_id, id)");
    }

    private void exec(String sql) {
        try {
            named.getJdbcOperations().execute(sql);
        } catch (Exception e) {
            // 列/索引已存在是常态，不打扰日志
            log.debug("[backtest] 迁移跳过：{}", e.getMessage());
        }
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public long insert(Backtest bt) {
        String sql = "INSERT INTO " + BT + " (user_id, stock_code, market, start_date, end_date, init_amount,"
                + " fee_rate, buy_rule, sell_rule, final_amount, total_profit, profit_ratio,"
                + " trade_count, trade_days) VALUES (:userId, :stockCode, :market, :startDate, :endDate,"
                + " :initAmount, :feeRate, :buyRule, :sellRule, :finalAmount, :totalProfit,"
                + " :profitRatio, :tradeCount, :tradeDays)";
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("userId", bt.getUserId())
                .addValue("stockCode", bt.getStockCode())
                .addValue("market", bt.getMarket())
                .addValue("startDate", bt.getStartDate())
                .addValue("endDate", bt.getEndDate())
                .addValue("initAmount", bt.getInitAmount())
                .addValue("feeRate", bt.getFeeRate())
                .addValue("buyRule", bt.getBuyRule())
                .addValue("sellRule", bt.getSellRule())
                .addValue("finalAmount", bt.getFinalAmount())
                .addValue("totalProfit", bt.getTotalProfit())
                .addValue("profitRatio", bt.getProfitRatio())
                .addValue("tradeCount", bt.getTradeCount())
                .addValue("tradeDays", bt.getTradeDays());
        KeyHolder kh = new GeneratedKeyHolder();
        named.update(sql, ps, kh, new String[]{"id"});
        Number key = kh.getKey();
        if (key == null) {
            throw new IllegalStateException("回测记录落库失败，未拿到自增主键");
        }
        return key.longValue();
    }

    @Override
    public void saveDailies(long backtestId, List<BacktestDaily> dailies) {
        if (dailies == null || dailies.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + BT_DAILY + " (backtest_id, trade_date, close_price, cash,"
                + " position_shares, position_value, total_amount, profit_amount, profit_ratio)"
                + " VALUES (:backtestId, :tradeDate, :closePrice, :cash, :positionShares,"
                + " :positionValue, :totalAmount, :profitAmount, :profitRatio)";
        SqlParameterSource[] batch = new SqlParameterSource[dailies.size()];
        for (int i = 0; i < dailies.size(); i++) {
            BacktestDaily d = dailies.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("backtestId", backtestId)
                    .addValue("tradeDate", d.getTradeDate())
                    .addValue("closePrice", d.getClosePrice())
                    .addValue("cash", d.getCash())
                    .addValue("positionShares", d.getPositionShares())
                    .addValue("positionValue", d.getPositionValue())
                    .addValue("totalAmount", d.getTotalAmount())
                    .addValue("profitAmount", d.getProfitAmount())
                    .addValue("profitRatio", d.getProfitRatio());
        }
        named.batchUpdate(sql, batch);
    }

    @Override
    public void saveTrades(long backtestId, List<BacktestTrade> trades) {
        if (trades == null || trades.isEmpty()) {
            return;
        }
        String sql = "INSERT INTO " + BT_TRADE + " (backtest_id, trade_date, action, shares, price,"
                + " turnover, fee, cash_after) VALUES (:backtestId, :tradeDate, :action, :shares,"
                + " :price, :turnover, :fee, :cashAfter)";
        SqlParameterSource[] batch = new SqlParameterSource[trades.size()];
        for (int i = 0; i < trades.size(); i++) {
            BacktestTrade t = trades.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("backtestId", backtestId)
                    .addValue("tradeDate", t.getTradeDate())
                    .addValue("action", t.getAction())
                    .addValue("shares", t.getShares())
                    .addValue("price", t.getPrice())
                    .addValue("turnover", t.getTurnover())
                    .addValue("fee", t.getFee())
                    .addValue("cashAfter", t.getCashAfter());
        }
        named.batchUpdate(sql, batch);
    }

    @Override
    public List<Backtest> list(long userId, int limit) {
        int lim = limit <= 0 ? 50 : Math.min(limit, 500);
        if (userId <= 0) {
            return new java.util.ArrayList<>();
        }
        // user_id = 0 的老记录只对超管可见；SQL 里直接带上超管 id，省得把别人的记录查出来再过滤
        long adminId = ownership.adminId();
        MapSqlParameterSource ps = new MapSqlParameterSource()
                .addValue("uid", userId)
                .addValue("adminId", adminId)
                .addValue("lim", lim);
        return named.query("SELECT " + BT_COLUMNS + " FROM " + BT
                        + " WHERE user_id = :uid OR (user_id <= 0 AND :adminId > 0 AND :uid = :adminId)"
                        + " ORDER BY id DESC LIMIT :lim",
                ps, new BeanPropertyRowMapper<>(Backtest.class));
    }

    @Override
    public BacktestResult detail(long userId, long id) {
        List<Backtest> head = named.query("SELECT " + BT_COLUMNS + " FROM " + BT + " WHERE id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(Backtest.class));
        if (head.isEmpty()) {
            return null;
        }
        Backtest bt = head.get(0);
        // 别人的记录等同于不存在：不暴露「有这条但你没权限」
        if (!ownership.visible(bt.getUserId(), userId)) {
            return null;
        }

        List<BacktestDaily> dailies = named.query("SELECT id, backtest_id,"
                        + " DATE_FORMAT(trade_date, '%Y-%m-%d') AS trade_date, close_price, cash,"
                        + " position_shares, position_value, total_amount, profit_amount, profit_ratio"
                        + " FROM " + BT_DAILY + " WHERE backtest_id = :id ORDER BY trade_date ASC",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(BacktestDaily.class));

        List<BacktestTrade> trades = named.query("SELECT id, backtest_id,"
                        + " DATE_FORMAT(trade_date, '%Y-%m-%d') AS trade_date, action, shares, price,"
                        + " turnover, fee, cash_after FROM " + BT_TRADE
                        + " WHERE backtest_id = :id ORDER BY trade_date ASC, id ASC",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(BacktestTrade.class));

        // 区间K线用于画图；K线属于「不落库但有落点」的数据，统一走 KlineRepository 取
        List<StockKlineDaily> klines = klineRepository.list(bt.getMarket(), bt.getStockCode(),
                bt.getStartDate(), bt.getEndDate());

        BacktestResult result = new BacktestResult();
        result.setBacktest(bt);
        result.setDailies(dailies);
        result.setTrades(trades);
        result.setKlines(klines == null ? new java.util.ArrayList<>() : klines);
        return result;
    }

    @Override
    public void delete(long userId, long id) {
        List<Backtest> head = named.query("SELECT " + BT_COLUMNS + " FROM " + BT + " WHERE id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(Backtest.class));
        if (head.isEmpty() || !ownership.visible(head.get(0).getUserId(), userId)) {
            return;
        }
        named.update("DELETE FROM " + BT_DAILY + " WHERE backtest_id = :id",
                new MapSqlParameterSource("id", id));
        named.update("DELETE FROM " + BT_TRADE + " WHERE backtest_id = :id",
                new MapSqlParameterSource("id", id));
        // 最后一条 SQL 带上 user_id 双保险：并发下即使有人抢先改了归属，也删不到别人的记录
        named.update("DELETE FROM " + BT + " WHERE id = :id AND (user_id = :uid OR (user_id <= 0 AND :uid = :adminId))",
                new MapSqlParameterSource("id", id).addValue("uid", userId)
                        .addValue("adminId", ownership.adminId()));
    }
}
