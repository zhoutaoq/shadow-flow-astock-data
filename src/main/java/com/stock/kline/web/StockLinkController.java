package com.stock.kline.web;

import com.stock.kline.model.ApiResponse;
import com.stock.kline.model.StockLink;
import com.stock.kline.service.StockLinkService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 股票名称跳转配置的读取接口（需求 v1.5.8 第 1 条）。
 *
 * <p>必须免登录：全站每个页面（含首页、股票列表、K线查看这些匿名入口）都要用它
 * 才能把名称渲染成链接。它只回「模板 + 开关」，不含任何账号数据，
 * 未登录看到的内容与登录后完全一样。
 */
@RestController
@RequestMapping("/api/stock-link")
public class StockLinkController {

    private final StockLinkService service;

    public StockLinkController(StockLinkService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<StockLink> current() {
        return ApiResponse.ok(service.current());
    }
}
