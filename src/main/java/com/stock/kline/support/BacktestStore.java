package com.stock.kline.support;

import com.stock.kline.model.Backtest;
import com.stock.kline.model.BacktestDaily;
import com.stock.kline.model.BacktestResult;
import com.stock.kline.model.BacktestTrade;

import java.util.List;

/**
 * 回测结果的落点。
 *
 * <p>无数据库模式下回测照常跑、照常返回结果，只是不留存历史。
 */
public interface BacktestStore {

    /** 是否落盘；false 时前端不展示"历史回测"入口 */
    boolean persistent();

    /** 写入主表并返回自增主键；不落盘的实现返回 0 */
    long insert(Backtest bt);

    void saveDailies(long backtestId, List<BacktestDaily> dailies);

    void saveTrades(long backtestId, List<BacktestTrade> trades);

    /** 历史回测列表；不落盘的实现返回空列表 */
    List<Backtest> list(int limit);

    /** 回看某次回测；不存在返回 null */
    BacktestResult detail(long id);

    void delete(long id);
}
