package com.stock.kline.repo;

import com.stock.kline.client.RawKline;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.support.KlineWindow;

import java.util.List;
import java.util.Map;

/**
 * 日K数据的统一仓储。
 *
 * <p>v1.3 后两种模式共用这一个接口，差别只在策略：
 * <ul>
 *   <li><b>数据库模式</b>（{@link DbKlineRepository}）：读日K表，缺的日期先同步补上再读，抓到的行落库；</li>
 *   <li><b>无数据库模式</b>（{@link LiveKlineRepository}）：直接向供应商取，现取现用，
 *       <b>不落盘</b> —— K线是设计里点名「不持久化」的数据。</li>
 * </ul>
 *
 * <p>回测、K线图、抓取三个入口都只依赖这个接口，不感知当前模式。
 */
public interface KlineRepository {

    /**
     * 取 [start, end] 区间数据，并按需要在 start 之前多取 warmup 个交易日做指标预热。
     *
     * <p>返回的行按交易日正序，前 preSize 行是预热段（不产生买卖信号）。
     */
    KlineWindow load(String market, String code, String start, String end, int warmup);

    /**
     * 起止两天里拿不到数据的日期。
     *
     * <p>无数据库模式永远是空 —— 数据现取，取不到就是供应商没有，补也没用。
     */
    List<String> missing(String market, String code, String start, String end);

    /**
     * 补齐某一天的数据。
     *
     * @throws IllegalStateException 同步失败
     */
    void prepareOne(String market, String code, String date);

    /**
     * 该股票可查范围的日期跨度（minDate / maxDate / count）。
     */
    Map<String, Object> span(String market, String code);

    /** 区间内的日K，按交易日升序（回测回看时画图用） */
    List<StockKlineDaily> list(String market, String code, String start, String end);

    /**
     * 把抓到的原始行写进当前模式的落点。
     *
     * @return 处理条数；无数据库模式下 K 线不落盘，固定返回 0
     */
    int save(List<RawKline> rows);
}
