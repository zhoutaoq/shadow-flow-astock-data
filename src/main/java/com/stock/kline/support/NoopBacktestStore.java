package com.stock.kline.support;

import com.stock.kline.model.Backtest;
import com.stock.kline.model.BacktestDaily;
import com.stock.kline.model.BacktestResult;
import com.stock.kline.model.BacktestTrade;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 无数据库模式：回测照常跑完并返回结果，只是什么都不存。
 *
 * <p>insert 固定返回 0 —— 前端看到 id 为 0 就知道这次结果没有历史可回看。
 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class NoopBacktestStore implements BacktestStore {

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public long insert(Backtest bt) {
        return 0;
    }

    @Override
    public void saveDailies(long backtestId, List<BacktestDaily> dailies) {
    }

    @Override
    public void saveTrades(long backtestId, List<BacktestTrade> trades) {
    }

    @Override
    public List<Backtest> list(int limit) {
        return new ArrayList<>();
    }

    @Override
    public BacktestResult detail(long id) {
        return null;
    }

    @Override
    public void delete(long id) {
    }
}
