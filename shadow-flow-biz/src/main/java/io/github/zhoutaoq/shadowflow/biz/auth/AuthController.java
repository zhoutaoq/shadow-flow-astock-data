package io.github.zhoutaoq.shadowflow.biz.auth;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.auth.model.UserView;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号接口：注册、登录、退出、当前用户、改密。
 *
 * <p>只有 {@code /logout} 与 {@code /password} 需要登录（见 {@link AuthWebConfig}），
 * 其余都是匿名可用 —— 首页与 K线不登录也要能打开，登录态是「有没有」而不是「是不是」。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final SessionService sessions;
    private final GitHubOAuthService github;

    public AuthController(AuthService authService, SessionService sessions, GitHubOAuthService github) {
        this.authService = authService;
        this.sessions = sessions;
        this.github = github;
    }

    /** 注册：用户名必须是邮箱且唯一，密码 ≥6 位且含字母和数字 */
    @PostMapping("/register")
    public ApiResponse<Map<String, Object>> register(@RequestBody(required = false) Map<String, Object> body) {
        String username = str(body, "username");
        String password = str(body, "password");
        User u = authService.register(username, password);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("user", UserView.of(u));
        return ApiResponse.ok(data, "注册成功，请登录");
    }

    /** 登录，成功返回令牌 */
    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody(required = false) Map<String, Object> body) {
        SessionService.Session s = authService.login(str(body, "username"), str(body, "password"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", s.getToken());
        data.put("expiresInSeconds", s.expiresInSeconds());
        User u = new User();
        u.setId(s.getUserId());
        u.setUsername(s.getUsername());
        u.setRole(s.getRole());
        data.put("user", UserView.of(u));
        return ApiResponse.ok(data, "登录成功");
    }

    /** 退出登录：令牌立即作废 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        authService.logout(token);
        return ApiResponse.ok(null, "已退出登录");
    }

    /**
     * 当前登录用户。
     *
     * <p>匿名访问也返回 200，用 {@code loggedIn} 区分 —— 前端每个页面都要问一次，
     * 返回 401 会在控制台刷一片红。
     *
     * <p>代理登录中额外返回 {@code impersonator}（代理者的用户名）：每个页面的顶栏都要
     * 显示一条「正在以谁的身份查看 + 退出」，所以这个信息必须从这里就能拿到。
     */
    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(
            @RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        SessionService.Session s = sessions.get(token);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("loggedIn", s != null);
        if (s != null) {
            User u = new User();
            u.setId(s.getUserId());
            u.setUsername(s.getUsername());
            u.setRole(s.getRole());
            data.put("user", UserView.of(u));
            if (s.isImpersonated()) {
                Map<String, Object> imp = new LinkedHashMap<>();
                imp.put("id", s.getImpersonatorId());
                imp.put("username", s.getImpersonatorUsername());
                data.put("impersonator", imp);
            }
        }
        data.put("registrationEnabled", authService.registrationEnabled());
        // GitHub 三方登录是否开通：登录弹窗据此决定显不显示 GitHub 按钮
        data.put("githubLoginEnabled", github.isConfigured());
        data.put("rules", AuthService.rules());
        return ApiResponse.ok(data);
    }

    /**
     * 退出代理登录：把超管原来的令牌还回去，并作废这次代理的登录态。
     *
     * <p>只有「本来就处在代理中」的登录态能调它 —— 令牌里记着代理者的用户 id 与原令牌，
     * 普通登录（包括被代理的那个用户自己登录）调它只会得到一句拒绝。
     */
    @PostMapping("/impersonate/stop")
    public ApiResponse<Map<String, Object>> stopImpersonate(
            @RequestHeader(value = AuthInterceptor.TOKEN_HEADER, required = false) String token) {
        SessionService.Session s = sessions.get(token);
        if (s == null || !s.isImpersonated()) {
            return ApiResponse.fail("当前不在代理登录中");
        }
        SessionService.Session back = sessions.get(s.getImpersonatorToken());
        if (back == null) {
            // 超管自己的登录态已经过期 / 已登出：代理态直接作废，让操作者重新登录
            sessions.remove(token);
            return ApiResponse.fail("原账号的登录已失效，请重新登录");
        }
        sessions.remove(token);
        log.info("[auth] {} 退出代理登录（代理 {}），已还原为 {}",
                s.getImpersonatorUsername(), s.getUsername(), back.getUsername());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", back.getToken());
        User u = new User();
        u.setId(back.getUserId());
        u.setUsername(back.getUsername());
        u.setRole(back.getRole());
        data.put("user", UserView.of(u));
        return ApiResponse.ok(data, "已退出代理登录");
    }

    /** 本人改密：需要原密码 */
    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody(required = false) Map<String, Object> body) {
        long userId = AuthContext.currentUserId();
        if (userId <= 0) {
            return ApiResponse.fail("请先登录");
        }
        String oldPwd = str(body, "oldPassword");
        String newPwd = str(body, "newPassword");
        authService.changePassword(userId, oldPwd, newPwd);
        return ApiResponse.ok(null, "密码已修改，请重新登录");
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null || body.get(key) == null) {
            return null;
        }
        return String.valueOf(body.get(key));
    }
}
