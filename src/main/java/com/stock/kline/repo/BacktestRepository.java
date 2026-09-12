package com.stock.kline.repo;

import com.stock.kline.model.Backtest;
import com.stock.kline.model.BacktestDaily;
import com.stock.kline.model.BacktestResult;
import com.stock.kline.model.BacktestTrade;

import java.util.List;

/**
 * 回测结果的统一仓储。
 *
 * <p>回测属于主流程（K线回测是两条主流程之一），所以两种模式都必须留存：
 * <ul>
 *   <li>数据库模式：主表 + 每日快照 + 交易明细三张表；</li>
 *   <li>无数据库模式：{@code ./data/backtest/bt-{id}.json}，一次回测一个文件，
 *       启动时全量载入内存，回看时K线由 {@link KlineRepository} 现取。</li>
 * </ul>
 */
public interface BacktestRepository {

    /** 是否落盘 */
    boolean persistent();

    /** 写入主记录并返回主键 */
    long insert(Backtest bt);

    void saveDailies(long backtestId, List<BacktestDaily> dailies);

    void saveTrades(long backtestId, List<BacktestTrade> trades);

    /** 历史回测列表，按 id 倒序 */
    List<Backtest> list(int limit);

    /** 回看某次回测；不存在返回 null */
    BacktestResult detail(long id);

    void delete(long id);
}
