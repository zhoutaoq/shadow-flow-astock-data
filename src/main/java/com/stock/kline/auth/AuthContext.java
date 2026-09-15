package com.stock.kline.auth;

import com.stock.kline.auth.model.User;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/**
 * 当前请求的用户。
 *
 * <p>由 {@code AuthInterceptor} 在放行前把 {@link User} 塞进 request 属性，
 * 业务代码（比如盯盘配置要按用户隔离）从这里取，不用每个接口都自己解析令牌。
 *
 * <p>未被拦截器放行的路径（首页、K线这类匿名可用的接口）取到的是 null。
 */
public final class AuthContext {

    /** request 属性名 */
    public static final String ATTR_USER = "auth.currentUser";
    /** 代理登录时，代理者（超管）的用户：request 属性名 */
    public static final String ATTR_IMPERSONATOR = "auth.impersonator";

    private AuthContext() {
    }

    /** 当前登录用户；未登录返回 null */
    public static User current() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        Object v = attrs.getAttribute(ATTR_USER, RequestAttributes.SCOPE_REQUEST);
        return v instanceof User ? (User) v : null;
    }

    /** 当前登录用户 id；未登录返回 0 */
    public static long currentUserId() {
        User u = current();
        return u == null || u.getId() == null ? 0L : u.getId();
    }

    /** 是否超管 */
    public static boolean isAdmin() {
        User u = current();
        return u != null && u.isAdmin();
    }

    /**
     * 代理登录中的代理者（超管）；不在代理中返回 null。
     *
     * <p>注意 {@link #current()} 给的是<b>被代理的那个用户</b> —— 这是故意的：
     * 所有按用户取数的接口都该按目标用户走。代理者身份只用于退出代理与日志留痕。
     */
    public static User impersonator() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return null;
        }
        Object v = attrs.getAttribute(ATTR_IMPERSONATOR, RequestAttributes.SCOPE_REQUEST);
        return v instanceof User ? (User) v : null;
    }

    /** 是否正处于代理登录中 */
    public static boolean isImpersonating() {
        return impersonator() != null;
    }
}
