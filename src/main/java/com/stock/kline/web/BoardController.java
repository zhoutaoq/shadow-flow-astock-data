package com.stock.kline.web;

import com.stock.kline.model.ApiResponse;
import com.stock.kline.news.BoardGroup;
import com.stock.kline.news.BoardService;
import com.stock.kline.news.HotStock;
import com.stock.kline.news.SectorRow;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 实时资讯页的两块数据：热门股排行 + 板块涨跌幅。
 *
 * <p>三家平台（东财 / 新浪 / 同花顺）各出一张卡片，具体某家支持哪块由
 * {@link BoardService#platforms()} 的能力位决定 —— 同花顺没有板块接口，
 * 所以它不会出现在板块结果里，前端因此不会摆一张永远空着的卡片。
 *
 * <p>取数失败时也返回 {@code success = true}（失败信息装在每张卡片的 {@code error} 里），
 * 与 {@link MarketController} 同一口径 —— 第三方接口抖动不该让整页报错，
 * 各卡片各自展示状态即可。
 *
 * <p>整个前缀匿名可用（未登记在 {@code AuthWebConfig.PROTECTED}），与首页 / K线查看一致：
 * 资讯是公开行情，且这几家上游本身也不需要令牌。
 */
@RestController
@RequestMapping("/api/board")
public class BoardController {

    private final BoardService service;

    public BoardController(BoardService service) {
        this.service = service;
    }

    /** 热门股排行，按平台名次出前 limit 条（默认 20，上限 50） */
    @GetMapping("/hot")
    public ApiResponse<List<BoardGroup<HotStock>>> hot(@RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.hotStocks(limit));
    }

    /** 板块涨跌幅（行业口径），按涨跌幅从高到低出前 limit 条（默认 20，上限 50） */
    @GetMapping("/sectors")
    public ApiResponse<List<BoardGroup<SectorRow>>> sectors(@RequestParam(value = "limit", required = false) Integer limit) {
        return ApiResponse.ok(service.sectors(limit));
    }

    /** 平台清单与能力位，页面据此决定每块渲染哪几张卡片 */
    @GetMapping("/platforms")
    public ApiResponse<List<Map<String, Object>>> platforms() {
        return ApiResponse.ok(service.platforms());
    }
}
