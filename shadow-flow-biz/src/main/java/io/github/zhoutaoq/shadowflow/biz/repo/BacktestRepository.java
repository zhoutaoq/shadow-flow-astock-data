package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.Backtest;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestDaily;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestResult;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestTrade;

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
 *
 * <p><b>v1.6.8 起所有查询都必须带 {@code userId}</b>（需求：回测记录按用户隔离）。
 * 记录自身的归属同样存在主记录里；{@code userId <= 0} 的是本字段引入之前的老记录，
 * 只对超管可见，判定规则见 {@link BacktestOwnership}。
 */
public interface BacktestRepository {

    /** 是否落盘 */
    boolean persistent();

    /** 写入主记录并返回主键（{@code bt.userId} 由调用方填当前登录用户） */
    long insert(Backtest bt);

    void saveDailies(long backtestId, List<BacktestDaily> dailies);

    void saveTrades(long backtestId, List<BacktestTrade> trades);

    /** 该用户的历史回测列表，按 id 倒序；别人的记录一条都不返回 */
    List<Backtest> list(long userId, int limit);

    /** 回看某次回测；不存在或不属于该用户都返回 null */
    BacktestResult detail(long userId, long id);

    /** 删除某次回测；不属于该用户时什么都不删 */
    void delete(long userId, long id);
}
