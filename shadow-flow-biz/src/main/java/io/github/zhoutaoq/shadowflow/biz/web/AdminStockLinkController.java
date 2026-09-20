package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.StockLink;
import io.github.zhoutaoq.shadowflow.biz.model.StockLinkSaveRequest;
import io.github.zhoutaoq.shadowflow.biz.service.StockLinkService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 股票名称跳转的系统级配置：只有超管能改。
 *
 * <p>与「首页背景行情」同一口径：路径挂在 {@code /api/admin/**} 下，
 * 拦截器已保证「已登录」，超管身份这里再判一次。
 */
@RestController
@RequestMapping("/api/admin/stock-link")
public class AdminStockLinkController {

    private final StockLinkService service;

    public AdminStockLinkController(StockLinkService service) {
        this.service = service;
    }

    /** 保存并立即生效：全站下一次取配置就用新的一份 */
    @PostMapping
    public ApiResponse<StockLink> save(@RequestBody StockLinkSaveRequest req) {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以修改系统配置");
        }
        return ApiResponse.ok(service.save(req), "已保存并生效");
    }

    /** 恢复默认：回到 application.yml 里的默认目标与开关 */
    @PostMapping("/reset")
    public ApiResponse<StockLink> reset() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以修改系统配置");
        }
        service.reset();
        return ApiResponse.ok(service.current(), "已恢复默认跳转配置");
    }
}
