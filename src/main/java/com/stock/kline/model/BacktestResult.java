package com.stock.kline.model;

import java.util.ArrayList;
import java.util.List;

/** 一次回测的完整结果：汇总 + 每日账户快照 + 交易明细 + 区间K线（画图用） */
public class BacktestResult {

    private Backtest backtest;
    private List<BacktestDaily> dailies = new ArrayList<>();
    private List<BacktestTrade> trades = new ArrayList<>();
    /** 回测区间内的日K（含 OHLC，用于画阶段K线与买卖点） */
    private List<StockKlineDaily> klines = new ArrayList<>();

    public Backtest getBacktest() {
        return backtest;
    }

    public void setBacktest(Backtest backtest) {
        this.backtest = backtest;
    }

    public List<BacktestDaily> getDailies() {
        return dailies;
    }

    public void setDailies(List<BacktestDaily> dailies) {
        this.dailies = dailies;
    }

    public List<BacktestTrade> getTrades() {
        return trades;
    }

    public void setTrades(List<BacktestTrade> trades) {
        this.trades = trades;
    }

    public List<StockKlineDaily> getKlines() {
        return klines;
    }

    public void setKlines(List<StockKlineDaily> klines) {
        this.klines = klines;
    }
}
