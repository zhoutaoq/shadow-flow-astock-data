package com.stock.kline.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.kline.auth.model.User;
import com.stock.kline.auth.repo.UserRepository;
import com.stock.kline.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 需要登录才能用的接口的守门人。
 *
 * <p>采用「显式登记受保护路径」而不是「全站拦截再放行白名单」——
 * 首页、K线、股票列表这些匿名也要能看，漏登记一个路径最多是少管一个接口，
 * 反过来误拦一个就会让匿名功能挂掉。
 *
 * <p>校验通过后把 {@link User} 放进 request 属性，
 * 业务代码从 {@link AuthContext} 取，不必再解析一次令牌。
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 前端统一带的请求头 */
    public static final String TOKEN_HEADER = "X-Auth-Token";

    private final SessionService sessions;
    private final UserRepository users;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthInterceptor(SessionService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        String token = tokenOf(request);
        SessionService.Session session = sessions.get(token);
        if (session == null) {
            reject(request, response, "请先登录");
            return false;
        }
        User u = users.findById(session.getUserId());
        if (u == null || !u.isEnabled()) {
            sessions.remove(token);
            reject(request, response, "登录已失效，请重新登录");
            return false;
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

    private void reject(HttpServletRequest request, HttpServletResponse response, String message)
            throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getCharacterEncoding();
        try {
            response.getOutputStream().write(
                    mapper.writeValueAsString(ApiResponse.fail(message)).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("[auth] 写入未登录响应失败: {}", e.getMessage());
        }
    }
}
