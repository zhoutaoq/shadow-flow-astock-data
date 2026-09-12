package com.stock.kline.auth;

import com.stock.kline.auth.model.UserView;
import com.stock.kline.model.ApiResponse;
import com.stock.kline.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户管理：只有超管能用。
 *
 * <p>拦截器只保证「已登录」，超管身份在这里再判一次 ——
 * 登录态里带着 role，但落到写操作上仍然显式校验，避免以后有人在别处
 * 注册了 {@code /api/admin/**} 路径却漏掉鉴权。
 *
 * <p>列表包含 admin 自己：要求里「分页展示系统用户，包括 admin 本身」，
 * 也方便超管直接在这个页面上改自己的口令。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final AuthService authService;

    public AdminUserController(AuthService authService) {
        this.authService = authService;
    }

    /** 用户分页；keyword 按邮箱模糊匹配 */
    @GetMapping
    public ApiResponse<PageResult<UserView>> page(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "20") int size,
            @RequestParam(name = "keyword", required = false) String keyword) {
        requireAdmin();
        return ApiResponse.ok(authService.userPage(page, size, keyword));
    }

    /**
     * 重置某人的密码（含 admin 自己）。
     *
     * <p>不校验原密码：超管不可能知道别人的口令。改完对方要重新登录一次。
     */
    @PutMapping("/{id}/password")
    public ApiResponse<Map<String, Object>> resetPassword(@PathVariable long id,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        requireAdmin();
        String password = body == null || body.get("password") == null
                ? null : String.valueOf(body.get("password"));
        authService.resetPassword(id, password);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", id);
        return ApiResponse.ok(data, "密码已重置");
    }

    private static void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以操作用户");
        }
    }
}
