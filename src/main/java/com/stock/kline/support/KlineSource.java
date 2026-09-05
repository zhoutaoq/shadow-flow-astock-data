package com.stock.kline.support;

import com.stock.kline.model.StockKlineDaily;

import java.util.List;
import java.util.Map;

/**
 * 日K数据源。
 *
 * <p>两种实现：
 * <ul>
 *   <li>数据库模式：读日K表，缺失的日期先同步再读</li>
 *   <li>无数据库模式：直接向供应商取，现取现用，不落盘</li>
 * </ul>
 */
public interface KlineSource {

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
     * 补齐某一天的数据（数据库模式走同步流程落盘，无数据库模式空转）。
     *
     * @throws IllegalStateException 同步失败
     */
    void prepareOne(String market, String code, String date);

    /**
     * 该股票可查范围的日期跨度（minDate / maxDate / count）。
     *
     * <p>无数据库模式没有"已入库"的概念，返回 count=0，页面改由用户直接给起止日期。
     */
    Map<String, Object> span(String market, String code);
}
