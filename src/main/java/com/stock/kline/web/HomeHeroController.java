package com.stock.kline.web;

import com.stock.kline.model.ApiResponse;
import com.stock.kline.model.HomeHero;
import com.stock.kline.service.HomeHeroService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页品牌带背景行情。
 *
 * <p>首页是匿名的，这个接口必须免登录 —— 未配置或临时取不到数据时返回
 * {@code configured = false} / 空 bars，页面据此回落到它自带的那段背景，
 * 既不报错也不留白。
 */
@RestController
@RequestMapping("/api/home")
public class HomeHeroController {

    private final HomeHeroService service;

    public HomeHeroController(HomeHeroService service) {
        this.service = service;
    }

    @GetMapping("/hero")
    public ApiResponse<HomeHero> hero() {
        return ApiResponse.ok(service.current());
    }
}
