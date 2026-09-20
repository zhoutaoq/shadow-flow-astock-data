package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.MenuConfig;
import io.github.zhoutaoq.shadowflow.biz.model.MenuSaveRequest;
import io.github.zhoutaoq.shadowflow.biz.service.MenuConfigService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 菜单配置的写入接口（需求 v1.6.2 第 1 条）：只有超管能改。
 *
 * <p>与「股票名称跳转」「首页背景行情」同一口径：路径挂在 {@code /api/admin/**} 下，
 * 拦截器已保证「已登录」，超管身份这里再判一次。
 *
 * <p>页面用「按钮 + 弹窗」的方式提供，保存后全站下一次加载即生效。
 */
@RestController
@RequestMapping("/api/admin/menu")
public class AdminMenuController {

    private final MenuConfigService service;

    public AdminMenuController(MenuConfigService service) {
        this.service = service;
    }

    /** 保存并立即生效：顺序与名称整份覆盖 */
    @PostMapping
    public ApiResponse<MenuConfig> save(@RequestBody MenuSaveRequest req) {
        requireAdmin();
        return ApiResponse.ok(service.save(req), "菜单已保存并生效");
    }

    /** 恢复默认：名称与顺序回到代码里的内置值 */
    @PostMapping("/reset")
    public ApiResponse<MenuConfig> reset() {
        requireAdmin();
        return ApiResponse.ok(service.reset(), "菜单已恢复默认");
    }

    private void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以修改系统配置");
        }
    }
}
