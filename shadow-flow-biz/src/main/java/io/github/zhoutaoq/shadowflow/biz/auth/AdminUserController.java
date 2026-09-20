package io.github.zhoutaoq.shadowflow.biz.auth;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.auth.model.UserView;
import io.github.zhoutaoq.shadowflow.biz.auth.repo.UserRepository;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
 *
 * <p>v1.5.2 加「代理登录」：超管可以切到某个用户身上，用他的眼睛看他的盯盘与账户。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    private static final Logger log = LoggerFactory.getLogger(AdminUserController.class);

    private final AuthService authService;
    private final UserRepository users;
    private final SessionService sessions;

    public AdminUserController(AuthService authService, UserRepository users, SessionService sessions) {
        this.authService = authService;
        this.users = users;
        this.sessions = sessions;
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

    /**
     * 代理登录：以指定用户的身份继续访问系统（v1.5.2）。
     *
     * <p>做法是<b>换一份该用户的登录态</b>而不是加一个"我现在是别人"的标志位 ——
     * 前者让所有按用户取数的接口（盯盘配置、账户、令牌）天然读到目标的，
     * 不需要任何一个业务接口知道代理这回事，也就不会有遗漏的地方。
     *
     * <p>返回的新令牌已经带着「代理者是谁、代理者原本的令牌是什么」，
     * 退出代理时由 {@code POST /api/auth/impersonate/stop} 还原。
     *
     * <p>不允许代理自己、也不允许代理另一个超管：前者没有意义，后者等于把超管权限绕一圈。
     */
    @PostMapping("/{id}/impersonate")
    public ApiResponse<Map<String, Object>> impersonate(
            @PathVariable long id,
            @RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        requireAdmin();
        SessionService.Session actor = sessions.get(token);
        if (actor == null) {
            return ApiResponse.fail("请先登录");
        }
        if (id == actor.getUserId()) {
            return ApiResponse.fail("不能代理登录自己");
        }
        User target = users.findById(id);
        if (target == null) {
            return ApiResponse.fail("用户不存在");
        }
        if (!target.isEnabled()) {
            return ApiResponse.fail("该账号已停用，无法代理登录");
        }
        if (target.isAdmin()) {
            return ApiResponse.fail("不能代理登录其它超管账号");
        }
        SessionService.Session s = sessions.create(target, actor);
        log.info("[auth] 超管 {} 代理登录 {}（{}）", actor.getUsername(), target.getUsername(), target.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", s.getToken());
        data.put("user", UserView.of(target));
        data.put("impersonator", actor.getUsername());
        return ApiResponse.ok(data, "已切换为 " + target.getUsername());
    }

    private static void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以操作用户");
        }
    }
}
