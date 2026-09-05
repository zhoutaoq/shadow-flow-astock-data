package com.stock.kline.support;

import com.stock.kline.config.WaizaoProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 无数据库模式的令牌来源：每次请求从请求头取。
 *
 * <p>后端不保存任何状态 —— 令牌由浏览器保存在本地，发请求时带上。
 * 请求头缺失时回落到 application.yml 的 waizao.api.token（方便命令行 / 脚本调用）。
 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class RequestTokenProvider implements TokenProvider {

    /** 浏览器每次请求带上的令牌请求头 */
    public static final String HEADER_TOKEN = "X-Waizao-Token";

    private final WaizaoProperties props;

    public RequestTokenProvider(WaizaoProperties props) {
        this.props = props;
    }

    @Override
    public String token() {
        RequestAttributes ra = RequestContextHolder.getRequestAttributes();
        if (ra instanceof ServletRequestAttributes) {
            String v = ((ServletRequestAttributes) ra).getRequest().getHeader(HEADER_TOKEN);
            if (StringUtils.hasText(v)) {
                return v.trim();
            }
        }
        String fallback = props.getApi().getToken();
        return fallback == null ? "" : fallback.trim();
    }
}
