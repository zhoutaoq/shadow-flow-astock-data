package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.MenuConfig;
import io.github.zhoutaoq.shadowflow.biz.service.MenuConfigService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单配置的读取接口（需求 v1.6.2 第 1 条）。
 *
 * <p>必须免登录：全站每个页面（含首页、股票列表这些匿名入口）都要用它
 * 才能把导航渲染出来。它只回「名称 + 顺序 + 地址」，不含任何账号数据，
 * 未登录看到的内容与登录后完全一样 —— 至于某一项该不该显示，由前端按内置的
 * 登录 / 超管标记自己裁剪，服务端不下发权限结论。
 */
@RestController
@RequestMapping("/api/menu")
public class MenuController {

    private final MenuConfigService service;

    public MenuController(MenuConfigService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<MenuConfig> current() {
        return ApiResponse.ok(service.current());
    }
}
