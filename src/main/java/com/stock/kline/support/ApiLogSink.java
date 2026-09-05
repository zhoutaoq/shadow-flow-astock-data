package com.stock.kline.support;

import com.stock.kline.model.ApiCallLog;

/**
 * 接口调用日志的落点。
 *
 * <p>无数据库模式下没有可写的地方，用空实现替代，调用方无需判断模式。
 */
public interface ApiLogSink {

    /**
     * @param item              日志内容
     * @param requestBeginMillis 请求开始时间戳，用于换算请求发生时间
     */
    void record(ApiCallLog item, long requestBeginMillis);
}
