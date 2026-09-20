package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.ApiCallLog;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;

import java.util.Map;

/**
 * 接口调用日志的统一仓储。
 *
 * <p>这是典型的「只增不减、行数很容易过万」的数据。按 v1.3 的设计约定：
 * <ul>
 *   <li>数据库模式：落 {@code shadowflow_api_call_log}，可分页查询与统计；</li>
 *   <li>无数据库模式：<b>直接丢弃</b>，只把调用情况打到应用日志。</li>
 * </ul>
 *
 * <p>它不属于主流程（K线回测、盯盘告警都不依赖它），所以丢掉不影响功能。
 * 请求次数仍能从 {@code app.log} 里事后统计。
 */
public interface ApiCallLogRepository {

    /** 是否落盘；false 表示当前模式下日志不留存 */
    boolean persistent();

    /**
     * 记录一次调用。
     *
     * @param item               日志内容
     * @param requestBeginMillis 请求发起时刻（毫秒），用它而非写日志时刻，
     *                            否则慢请求/超时请求记到的是结束时间
     */
    void record(ApiCallLog item, long requestBeginMillis);

    PageResult<ApiCallLog> page(String code, String market, String scene,
                                String reqStartDate, String reqEndDate,
                                String requestTimeFrom, String requestTimeTo, Integer success,
                                int page, int size);

    /** 调用次数与耗时概览（日志页顶部） */
    Map<String, Object> summary();
}
