package com.stock.kline.support;

import com.stock.kline.auth.AuthContext;
import com.stock.kline.auth.model.User;
import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.repo.SysConfigRepository;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 供应商令牌来源：按「发起这一请求的人」取，两种运行模式共用一份实现。
 *
 * <p>解析顺序（v1.5 起取消浏览器本地令牌与 <code>X-Waizao-Token</code> 请求头，
 * 令牌只认服务端配置）：
 * <ol>
 *   <li><b>当前登录用户自己的令牌</b>（{@code waizao_sys_config} 里 owner_id = 该用户的行）——
 *       每个用户一份，互不干扰；</li>
 *   <li><b>系统级令牌</b>（owner_id = 0）：未登录的请求（首页、K线查看等匿名接口），
 *       或登录用户还没配过自己的令牌时用它兜底；</li>
 *   <li>再没有才回落到 application.yml 的 {@code waizao.api.token}。</li>
 * </ol>
 *
 * <p>注意后台线程（盯盘调度、定时任务）没有请求上下文，{@link AuthContext#current()}
 * 恒为 null，走的就是第 2 步的系统级令牌 —— 所以「每个用户一份」只作用于人主动发起的请求。
 */
@Component
public class UserTokenProvider implements TokenProvider {

    private final SysConfigRepository sysConfig;
    private final WaizaoProperties props;

    public UserTokenProvider(SysConfigRepository sysConfig, WaizaoProperties props) {
        this.sysConfig = sysConfig;
        this.props = props;
    }

    @Override
    public String token() {
        User me = AuthContext.current();
        if (me != null && me.getId() != null && sysConfig != null) {
            String mine = sysConfig.waizaoToken(me.getId());
            if (StringUtils.hasText(mine)) {
                return mine.trim();
            }
        }
        String system = sysConfig == null ? null : sysConfig.waizaoToken();
        if (StringUtils.hasText(system)) {
            return system.trim();
        }
        String fallback = props.getApi() == null ? null : props.getApi().getToken();
        return fallback == null ? "" : fallback.trim();
    }
}
