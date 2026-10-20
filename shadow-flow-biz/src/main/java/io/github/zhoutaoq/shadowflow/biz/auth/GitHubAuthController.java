package io.github.zhoutaoq.shadowflow.biz.auth;

import io.github.zhoutaoq.shadowflow.biz.auth.model.GitHubProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import javax.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitHub 三方登录的入口与回调。
 *
 * <p>流程：登录页点「GitHub 登录」→ {@code GET /login} 跳 GitHub 授权页 → 用户同意后
 * GitHub 回调 {@code GET /callback} → 服务端换 token 拉资料、找/建系统用户、签发登录态
 * → 302 回站内（令牌拼在 URL fragment 里，前端收进 cookie 后清掉）。
 *
 * <p>令牌不走 query 而走 <b>fragment</b>（{@code #token=}）：fragment 不进服务端访问日志、
 * 不进 Referer，与「令牌只进 cookie、每个请求走 X-Auth-Token 头」的既有口径一致。
 */
@RestController
@RequestMapping("/api/auth/github")
public class GitHubAuthController {

    private static final Logger log = LoggerFactory.getLogger(GitHubAuthController.class);

    private final GitHubOAuthService github;
    private final AuthService authService;
    private final GitHubOAuthProperties props;

    public GitHubAuthController(GitHubOAuthService github, AuthService authService, GitHubOAuthProperties props) {
        this.github = github;
        this.authService = authService;
        this.props = props;
    }

    /**
     * 发起登录：302 到 GitHub 授权页。
     *
     * @param returnTo 登录完成后要回的站内路径（相对路径；外站地址会被丢弃回首页）
     */
    @GetMapping("/login")
    public RedirectView login(@RequestParam(name = "returnTo", required = false) String returnTo,
                              HttpServletRequest request) {
        if (!github.isConfigured()) {
            return errorRedirect("GitHub 登录尚未开通，请在 app.env 配置 GITHUB_CLIENT_ID / GITHUB_CLIENT_SECRET", returnTo);
        }
        RedirectView rv = new RedirectView(github.buildAuthorizeUrl(redirectUri(request), returnTo));
        rv.setExposeModelAttributes(false);
        return rv;
    }

    /**
     * GitHub 回调：换 token → 找/建用户 → 签发登录态 → 回站内。
     *
     * <p>所有失败路径都 302 回站内并把原因拼进 fragment（{@code #authError=}），
     * 不给用户留白页；GitHub 拒绝授权（error 参数）也算其中一种。
     */
    @GetMapping("/callback")
    public RedirectView callback(@RequestParam(name = "code", required = false) String code,
                                 @RequestParam(name = "state", required = false) String state,
                                 @RequestParam(name = "error", required = false) String error,
                                 @RequestParam(name = "error_description", required = false) String errorDescription,
                                 HttpServletRequest request) {
        String returnTo = "/";
        try {
            returnTo = github.consumeState(state);
            if (error != null && !error.trim().isEmpty()) {
                String desc = errorDescription == null || errorDescription.trim().isEmpty() ? error : errorDescription;
                throw new IllegalArgumentException("GitHub 授权被取消：" + desc);
            }
            if (code == null || code.trim().isEmpty()) {
                throw new IllegalArgumentException("GitHub 回调缺少授权码，请重新登录");
            }
            GitHubProfile profile = github.fetchProfile(code.trim(), redirectUri(request));
            SessionService.Session s = authService.loginByGithub(profile);
            // 令牌进 fragment 不进 query：服务端日志与 Referer 都看不到它
            RedirectView rv = new RedirectView(returnTo + "#token=" + s.getToken());
            rv.setExposeModelAttributes(false);
            return rv;
        } catch (IllegalArgumentException e) {
            log.warn("[auth] GitHub 登录失败：{}", e.getMessage());
            return errorRedirect(e.getMessage(), returnTo);
        } catch (Exception e) {
            log.warn("[auth] GitHub 登录异常", e);
            return errorRedirect("GitHub 登录失败，请稍后再试", returnTo);
        }
    }

    /** 当前是否开通 GitHub 登录（登录页据此决定显不显示按钮） */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", github.isConfigured());
        return m;
    }

    /** 失败也回站内页，原因拼 fragment，前端弹提示 */
    private RedirectView errorRedirect(String message, String returnTo) {
        String target = (returnTo == null || returnTo.trim().isEmpty()) ? "/" : returnTo.trim();
        if (!target.startsWith("/") || target.startsWith("//")) {
            target = "/";
        }
        RedirectView rv = new RedirectView(target + "#authError=" + encode(message));
        rv.setExposeModelAttributes(false);
        return rv;
    }

    /**
     * 回调地址：优先配置里写死的（反代后推导不准时用），否则按当前请求推导。
     *
     * <p>必须与 GitHub 后台登记的 Authorization callback URL 逐字符一致，否则 GitHub 直接拒。
     */
    private String redirectUri(HttpServletRequest request) {
        if (props.getRedirectUri() != null && !props.getRedirectUri().trim().isEmpty()) {
            return props.getRedirectUri().trim();
        }
        int port = request.getServerPort();
        String scheme = request.getScheme();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + request.getServerName() + (defaultPort ? "" : ":" + port)
                + "/api/auth/github/callback";
    }

    private static String encode(String v) {
        if (v == null) {
            return "";
        }
        try {
            return java.net.URLEncoder.encode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return v;
        }
    }
}
