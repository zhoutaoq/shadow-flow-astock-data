package com.stock.kline.auth;

import com.stock.kline.auth.repo.UserRepository;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 把登录校验挂到需要登录的接口上。
 *
 * <p>v1.3 的口径：<b>回测、盯盘、用户管理</b>要登录，
 * <b>首页与 K线查看</b>（以及它们依赖的股票列表、行情、模式接口）匿名可用。
 * 以后新增需要登录的接口，往 {@link #PROTECTED} 里加一行即可。
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    /**
     * 需要登录后才能访问的接口前缀。
     *
     * <p>回测、盯盘是明说的；抓取（{@code /api/fetch/**}）一起加进来是出于同一个理由 ——
     * 它按次向供应商计费，而「数据更新」那几个页面 v1.3 起已经从菜单里摘掉了，
     * 匿名留着等于给路人留了个能花钱的口子。
     */
    private static final String[] PROTECTED = {
            "/api/backtest/**",
            "/api/watch/**",
            "/api/fetch/**",
            "/api/admin/**",
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
        registry.addInterceptor(new AuthInterceptor(sessions, users)).addPathPatterns(PROTECTED);
    }
}
