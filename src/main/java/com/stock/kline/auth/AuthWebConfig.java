package com.stock.kline.auth;

import com.stock.kline.auth.repo.UserRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把登录态解析与登录校验挂到接口上。
 *
 * <p>v1.3 的口径：<b>回测、盯盘、用户管理</b>要登录，
 * <b>首页与 K线查看</b>（以及它们依赖的股票列表、行情、模式接口）匿名可用。
 * 以后新增需要登录的接口，往 {@link #PROTECTED} 里加一行即可。
 *
 * <p>v1.5 多挂一个「只解析不拦截」的实例在 {@code /api/**} 上：K线查看这类匿名也能用的接口，
 * 在用户已登录时要能按用户取数（用他自己的供应商令牌），所以登录态必须先解析出来；
 * 解析失败也只是放过，真正的拦截仍然只发生在 {@link #PROTECTED} 上。
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    /**
     * 需要登录后才能访问的接口前缀。
     *
     * <p>回测、盯盘是明说的；抓取（{@code /api/fetch/**}）一起加进来是出于同一个理由 ——
     * 它按次向供应商计费，而「数据更新」那几个页面 v1.3 起已经从菜单里摘掉了，
     * 匿名留着等于给路人留了个能花钱的口子。
     *
     * <p>v1.5 起「实时通知配置」（{@code /api/sys-config/**}）也要求登录 ——
     * 令牌是挂在用户名下的，页面上看到的、改的都是自己的那一份。
     */
    private static final String[] PROTECTED = {
            "/api/backtest/**",
            "/api/watch/**",
            "/api/fetch/**",
            "/api/admin/**",
            "/api/sys-config/**",
            "/api/auth/logout",
            "/api/auth/password"
    };

    private final SessionService sessions;
    private final UserRepository users;

    public AuthWebConfig(SessionService sessions, UserRepository users) {
        this.sessions = sessions;
        this.users = users;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 先解析登录态（不拦截），再按显式清单守卫
        registry.addInterceptor(new AuthInterceptor(sessions, users, false)).addPathPatterns("/api/**");
        registry.addInterceptor(new AuthInterceptor(sessions, users, true)).addPathPatterns(PROTECTED);
    }
}
