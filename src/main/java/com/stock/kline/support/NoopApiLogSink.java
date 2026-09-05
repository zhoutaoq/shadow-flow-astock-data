package com.stock.kline.support;

import com.stock.kline.model.ApiCallLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 无数据库模式：没有可写的日志表，只把调用情况打到应用日志里。
 *
 * <p>接口按次计费，请求次数是需要关心的信息，丢到日志文件里也能事后统计。
 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class NoopApiLogSink implements ApiLogSink {

    private static final Logger log = LoggerFactory.getLogger(NoopApiLogSink.class);

    @Override
    public void record(ApiCallLog item, long requestBeginMillis) {
        if (log.isInfoEnabled()) {
            log.info("[waizao] call scene={} code={} market={} range=[{}~{}] status={} rows={} cost={}ms err={}",
                    item.getScene(), item.getStockCode(), item.getMarket(), item.getReqStartDate(),
                    item.getReqEndDate(), item.getHttpStatus(), item.getRowCount(), item.getCostMs(),
                    item.getErrorMsg());
        }
    }
}
