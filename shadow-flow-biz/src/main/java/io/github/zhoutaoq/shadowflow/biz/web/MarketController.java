package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.market.MarketIndex;
import io.github.zhoutaoq.shadowflow.biz.market.MarketIndexService;
import io.github.zhoutaoq.shadowflow.biz.market.MarketTurnoverService;
import io.github.zhoutaoq.shadowflow.biz.market.RealtimeBoardService;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

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
    private final MarketTurnoverService turnover;
    private final RealtimeBoardService realtime;

    public MarketController(MarketIndexService service, MarketTurnoverService turnover,
                            RealtimeBoardService realtime) {
        this.service = service;
        this.turnover = turnover;
        this.realtime = realtime;
    }

    @GetMapping("/indices")
    public ApiResponse<List<MarketIndex>> indices() {
        return ApiResponse.ok(service.indices());
    }

    /**
     * 行业板块成交额 / 涨跌幅 + 全市场涨跌、涨跌停家数（「实时行情查看」页）。
     *
     * <p>同样匿名可读，只读公开行情。
     */
    @GetMapping("/realtime")
    public ApiResponse<Map<String, Object>> realtime() {
        return ApiResponse.ok(realtime.snapshot());
    }

    /**
     * 沪深主板 / 创业板成交额 + 较昨日同期的增减（首页「市场成交」卡）。
     *
     * <p>与指数一样匿名可看：只读公开行情，不含任何用户数据。
     */
    @GetMapping("/turnover")
    public ApiResponse<Map<String, Object>> turnover() {
        return ApiResponse.ok(turnover.turnover());
    }
}
