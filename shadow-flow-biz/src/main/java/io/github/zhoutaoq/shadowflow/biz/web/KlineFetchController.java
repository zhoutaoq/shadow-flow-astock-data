package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.service.KlineFetchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 抓取 / 调试接口。
 *
 * <p>所有抓取入口都支持 dryRun：true 时只请求供应商并回吐结果，不落盘。
 */
@RestController
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
@RequestMapping("/api/fetch")
public class KlineFetchController {

    private final KlineFetchService service;

    public KlineFetchController(KlineFetchService service) {
        this.service = service;
    }

    /**
     * 通用抓取：单只（codes 传 1 个）或多只（codes 传多个）。
     * dryRun=true 为调试模式（不落盘），false 为落盘模式。
     */
    @PostMapping
    public ApiResponse<KlineFetchService.FetchResult> fetch(@RequestBody KlineFetchService.FetchRequest req) {
        if (req == null) {
            return ApiResponse.fail("请求体不能为空");
        }
        try {
            return ApiResponse.ok(service.fetch(req));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("更新失败: " + e.getMessage());
        }
    }

    /**
     * 原始接口调试：回吐请求 URL + 供应商原始响应体 + 解析结果，调用日志标记为 dev。
     *
     * @param persist 是否把解析结果落盘，由调试页开关键控制；false 时只渲染不写库
     */
    @GetMapping("/raw")
    public ApiResponse<Map<String, Object>> raw(
            @RequestParam String code,
            @RequestParam(defaultValue = "stock") String market,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "false") boolean persist) {
        try {
            return ApiResponse.ok(service.rawQuery(market, code, startDate, endDate, persist));
        } catch (Exception e) {
            return ApiResponse.fail("请求失败: " + e.getMessage());
        }
    }
}
