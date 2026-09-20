package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.ApiCallLog;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 无数据库模式：调用日志<b>不落盘</b>，只打到应用日志。
 *
 * <p>为什么不按「统一落文件」处理：调用日志只会随抓取次数无限增长，几万行是常态，
 * 属于设计里明确点名的「数据行超过一万」一类。它又不参与主流程（回测与盯盘都不读它），
 * 为它维护一份越来越大的文件表不划算 —— 真要统计请求次数，{@code app.log} 里都有。
 */
@Repository
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class DiscardApiCallLogRepository implements ApiCallLogRepository {

    private static final Logger log = LoggerFactory.getLogger(DiscardApiCallLogRepository.class);

    @Override
    public boolean persistent() {
        return false;
    }

    @Override
    public void record(ApiCallLog item, long requestBeginMillis) {
        if (item == null) {
            return;
        }
        if (log.isInfoEnabled()) {
            log.info("[market-data] call scene={} code={} market={} range=[{}~{}] status={} rows={} cost={}ms err={}",
                    item.getScene(), item.getStockCode(), item.getMarket(), item.getReqStartDate(),
                    item.getReqEndDate(), item.getHttpStatus(), item.getRowCount(), item.getCostMs(),
                    item.getErrorMsg());
        }
    }

    @Override
    public PageResult<ApiCallLog> page(String code, String market, String scene,
                                       String reqStartDate, String reqEndDate,
                                       String requestTimeFrom, String requestTimeTo, Integer success,
                                       int page, int size) {
        // 不落盘就没有历史可查，返回空页而不是报错
        return new PageResult<>(new ArrayList<>(), 0, page <= 0 ? 1 : page, size <= 0 ? 20 : size);
    }

    @Override
    public Map<String, Object> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", 0L);
        data.put("failed", 0L);
        data.put("success", 0L);
        data.put("avgCostMs", 0);
        data.put("byScene", Collections.emptyMap());
        data.put("discarded", true);
        return data;
    }
}
