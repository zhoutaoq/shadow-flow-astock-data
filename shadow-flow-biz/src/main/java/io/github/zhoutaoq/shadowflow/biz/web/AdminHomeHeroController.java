package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.HomeHero;
import io.github.zhoutaoq.shadowflow.biz.model.HomeHeroRequest;
import io.github.zhoutaoq.shadowflow.biz.service.HomeHeroService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 首页背景行情的系统级配置：只有超管能改。
 *
 * <p>路径挂在 {@code /api/admin/**} 下，拦截器已保证「已登录」；
 * 超管身份在这里再判一次（与用户管理同一口径），避免以后有人在别处
 * 注册了同前缀路径却漏掉鉴权。
 */
@RestController
@RequestMapping("/api/admin/home-hero")
public class AdminHomeHeroController {

    private final HomeHeroService service;

    public AdminHomeHeroController(HomeHeroService service) {
        this.service = service;
    }

    /** 保存并立即生效：先把这段区间的行情取回来，取不到就不落配置 */
    @PostMapping
    public ApiResponse<HomeHero> save(@RequestBody HomeHeroRequest req) {
        requireAdmin();
        if (req == null) {
            return ApiResponse.fail("请求体不能为空");
        }
        HomeHero hero = service.save(req.getCode(), req.getName(),
                req.getStartDate(), req.getEndDate());
        return ApiResponse.ok(hero, "已保存，首页背景已更新");
    }

    /** 恢复默认背景 */
    @PostMapping("/reset")
    public ApiResponse<HomeHero> reset() {
        requireAdmin();
        service.reset();
        return ApiResponse.ok(service.current(), "已恢复默认背景");
    }

    private static void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以修改系统配置");
        }
    }
}
