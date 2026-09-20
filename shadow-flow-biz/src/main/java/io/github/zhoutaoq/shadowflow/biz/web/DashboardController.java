package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.service.StockKlineService;
import io.github.zhoutaoq.shadowflow.biz.repo.StockListRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 首页概览统计 */
@RestController
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final StockListRepository stockListRepository;
    private final StockKlineService klineService;

    public DashboardController(StockListRepository stockListRepository, StockKlineService klineService) {
        this.stockListRepository = stockListRepository;
        this.klineService = klineService;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("stockCount", stockListRepository.count());
        data.put("klineCount", klineService.count());
        data.put("coveredStockCount", klineService.countGroupByCode().size());
        return ApiResponse.ok(data);
    }
}
