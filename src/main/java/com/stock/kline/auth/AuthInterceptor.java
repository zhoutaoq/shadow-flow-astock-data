package com.stock.kline.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.kline.auth.model.User;
import com.stock.kline.auth.repo.UserRepository;
import com.stock.kline.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 登录态解析与守门。
 *
 * <p>同一份实现挂两次（见 {@code AuthWebConfig}）：
 * <ul>
 *   <li><b>解析态</b>（{@code required = false}）挂在 {@code /api/**} 上：带了有效登录令牌就把
 *       {@link User} 放进 request 属性，没带或无效也只是放过 —— 让 K线查看、股票列表这些
 *       匿名可用的接口在「已登录」时也能按用户取数（比如用自己的供应商令牌）；
 *       路由本身不需要登录的，绝不能在这里被拦下。</li>
 *   <li><b>守着</b>（{@code required = true}）只挂在需要登录的路径上，没登录就 401。</li>
 * </ul>
 *
 * <p>采用「显式登记受保护路径」而不是「全站拦截再放行白名单」——
 * 首页、K线、股票列表这些匿名也要能看，漏登记一个路径最多是少管一个接口，
 * <p>不作为 bean 自动装配（{@code AuthWebConfig} 里 new 两个实例、各自指定是否拦截），
 * 所以这里没有 {@code @Component}。
 */
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 前端统一带的请求头 */
    public static final String TOKEN_HEADER = "X-Auth-Token";

    private final SessionService sessions;
    private final UserRepository users;
    /** true = 没登录直接 401；false = 只解析登录态，不登录也放行 */
    private final boolean required;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthInterceptor(SessionService sessions, UserRepository users) {
        this(sessions, users, true);
    }

    public AuthInterceptor(SessionService sessions, UserRepository users, boolean required) {
        this.sessions = sessions;
        this.users = users;
        this.required = required;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String token = tokenOf(request);
        SessionService.Session session = sessions.get(token);
        if (session == null) {
            return !required || reject(response, "请先登录");
        }
        User u = users.findById(session.getUserId());
        if (u == null || !u.isEnabled()) {
            sessions.remove(token);
            return !required || reject(response, "登录已失效，请重新登录");
        }
        request.setAttribute(AuthContext.ATTR_USER, u);
        return true;
    }

    /** 请求头优先，其次 URL 参数（浏览器直接打开一个链接时方便带一次） */
    private static String tokenOf(HttpServletRequest request) {
        String t = request.getHeader(TOKEN_HEADER);
        if (t == null || t.trim().isEmpty()) {
            t = request.getParameter("token");
        }
        return t == null ? "" : t.trim();
    }

    /** 回 401；返回 false 便于调用处直接 `return` */
    private boolean reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getCharacterEncoding();
        try {
            response.getOutputStream().write(
                    mapper.writeValueAsString(ApiResponse.fail(message)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[auth] 写入未登录响应失败: {}", e.getMessage());
        }
        return false;
    }
}
