package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiCallLog;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.repo.ApiCallLogRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 接口调用日志 shadowflow_api_call_log 查询 */
@RestController
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
@RequestMapping("/api/call-logs")
public class ApiCallLogController {

    private final ApiCallLogRepository repository;

    public ApiCallLogController(ApiCallLogRepository repository) {
        this.repository = repository;
    }

    /**
     * 分页查询调用日志
     *
     * @param code            股票代码
     * @param market          市场/品种
     * @param scene           调用场景：dev-调试 batch-批处理
     * @param reqStartDate    请求日期参数-起（与填写区间有交集即命中）
     * @param reqEndDate      请求日期参数-止
     * @param requestTimeFrom 请求发生时间-起，支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm[:ss]
     * @param requestTimeTo   请求发生时间-止
     * @param success         1-仅成功 0-仅失败
     */
    @GetMapping
    public ApiResponse<PageResult<ApiCallLog>> page(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String scene,
            @RequestParam(required = false) String reqStartDate,
            @RequestParam(required = false) String reqEndDate,
            @RequestParam(required = false) String requestTimeFrom,
            @RequestParam(required = false) String requestTimeTo,
            @RequestParam(required = false) Integer success,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size <= 0) {
            size = 20;
        }
        if (size > 5000) {
            size = 5000;
        }
        return ApiResponse.ok(repository.page(code, market, scene, reqStartDate, reqEndDate,
                requestTimeFrom, requestTimeTo, success, page, size));
    }

    /** 调用次数与耗时概览（含按场景拆分） */
    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        return ApiResponse.ok(repository.summary());
    }
}
