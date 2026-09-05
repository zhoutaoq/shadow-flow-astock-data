package com.stock.kline.support;

import com.stock.kline.model.ApiCallLog;
import com.stock.kline.service.ApiCallLogService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 数据库模式：调用日志落 waizao_api_call_log */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbApiLogSink implements ApiLogSink {

    private final ApiCallLogService service;

    public DbApiLogSink(ApiCallLogService service) {
        this.service = service;
    }

    @Override
    public void record(ApiCallLog item, long requestBeginMillis) {
        service.record(item, requestBeginMillis);
    }
}
