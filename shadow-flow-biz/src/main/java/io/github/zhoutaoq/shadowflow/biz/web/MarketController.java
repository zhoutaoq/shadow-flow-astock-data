package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.market.MarketIndex;
import io.github.zhoutaoq.shadowflow.biz.market.MarketIndexService;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 大盘指数行情：给首页顶部的指数条用。
 *
 * <p>取不到时返回空数组（success 仍为 true）—— 首页据此把指数区整块隐藏，
 * 不弹错误、不留空洞，避免第三方接口抖动影响主流程。
 */
@RestController
@RequestMapping("/api/market")
public class MarketController {

    private final MarketIndexService service;

    public MarketController(MarketIndexService service) {
        this.service = service;
    }

    @GetMapping("/indices")
    public ApiResponse<List<MarketIndex>> indices() {
        return ApiResponse.ok(service.indices());
    }
}
