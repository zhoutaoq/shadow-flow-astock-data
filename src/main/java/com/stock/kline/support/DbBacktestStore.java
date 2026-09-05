package com.stock.kline.support;

import com.stock.kline.model.Backtest;
import com.stock.kline.model.BacktestDaily;
import com.stock.kline.model.BacktestResult;
import com.stock.kline.model.BacktestTrade;
import com.stock.kline.model.StockKlineDaily;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 数据库模式：回测结果落 waizao_backtest / _daily / _trade 三张表 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbBacktestStore implements BacktestStore {

    private static final String KLINE = "waizao_stock_kline_daily";
    private static final String BT = "waizao_backtest";
    private static final String BT_DAILY = "waizao_backtest_daily";
    private static final String BT_TRADE = "waizao_backtest_trade";

    private static final String BT_COLUMNS = "id, stock_code, market,"
            + " DATE_FORMAT(start_date, '%Y-%m-%d') AS start_date,"
            + " DATE_FORMAT(end_date, '%Y-%m-%d') AS end_date,"
            + " init_amount, fee_rate, buy_rule, sell_rule, final_amount, total_profit,"
            + " profit_ratio, trade_count, trade_days,"
            + " DATE_FORMAT(create_time, '%Y-%m-%d %H:%i:%s') AS create_time";

    private final NamedParameterJdbcTemplate named;

    public DbBacktestStore(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public long insert(Backtest bt) {
        String sql = "INSERT INTO " + BT + " (stock_code, market, start_date, end_date, init_amount,"
                + " fee_rate, buy_rule, sell_rule, final_amount, total_profit, profit_ratio,"
                + " trade_count, trade_days) VALUES (:stockCode, :market, :startDate, :endDate,"
                + " :initAmount, :feeRate, :buyRule, :sellRule, :finalAmount, :totalProfit,"
                + " :profitRatio, :tradeCount, :tradeDays)";
        MapSqlParameterSource ps = new MapSqlParameterSource()
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
    public List<Backtest> list(int limit) {
        int lim = limit <= 0 ? 50 : Math.min(limit, 500);
        return named.query("SELECT " + BT_COLUMNS + " FROM " + BT + " ORDER BY id DESC LIMIT :lim",
                new MapSqlParameterSource("lim", lim), new BeanPropertyRowMapper<>(Backtest.class));
    }

    @Override
    public BacktestResult detail(long id) {
        List<Backtest> head = named.query("SELECT " + BT_COLUMNS + " FROM " + BT + " WHERE id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(Backtest.class));
        if (head.isEmpty()) {
            return null;
        }
        Backtest bt = head.get(0);

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

        List<StockKlineDaily> klines = named.query("SELECT trade_date, open_price, close_price, high_price,"
                        + " low_price, volume, amount, pre_close FROM " + KLINE
                        + " WHERE stock_code = :code AND market = :market"
                        + " AND trade_date BETWEEN :start AND :end ORDER BY trade_date ASC",
                new MapSqlParameterSource()
                        .addValue("code", bt.getStockCode())
                        .addValue("market", bt.getMarket())
                        .addValue("start", bt.getStartDate())
                        .addValue("end", bt.getEndDate()),
                new BeanPropertyRowMapper<>(StockKlineDaily.class));

        BacktestResult result = new BacktestResult();
        result.setBacktest(bt);
        result.setDailies(dailies);
        result.setTrades(trades);
        result.setKlines(klines);
        return result;
    }

    @Override
    public void delete(long id) {
        named.update("DELETE FROM " + BT_DAILY + " WHERE backtest_id = :id",
                new MapSqlParameterSource("id", id));
        named.update("DELETE FROM " + BT_TRADE + " WHERE backtest_id = :id",
                new MapSqlParameterSource("id", id));
        named.update("DELETE FROM " + BT + " WHERE id = :id", new MapSqlParameterSource("id", id));
    }
}
