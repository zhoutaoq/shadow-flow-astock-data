package com.stock.kline.support;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.repo.SysConfigRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 无数据库模式的令牌来源：请求头 → 配置文件 → application.yml。
 *
 * <p>顺序说明：
 * <ol>
 *   <li>浏览器每次请求带上的 {@code X-Waizao-Token}（令牌保存在浏览器本地，不下发到后端存储）；</li>
 *   <li>没有请求头（命令行 / 定时脚本调用）时，读 {@code ./data/sys-config.json} 里保存的令牌 ——
 *       v1.3 起配置在无数据库模式下也落盘了，页面「系统配置」保存后即可长期生效；</li>
 *   <li>再没有才回落到 application.yml 的 {@code waizao.api.token}。</li>
 * </ol>
 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class RequestTokenProvider implements TokenProvider {

    /** 浏览器每次请求带上的令牌请求头 */
    public static final String HEADER_TOKEN = "X-Waizao-Token";

    private final SysConfigRepository sysConfig;
    private final WaizaoProperties props;

    public RequestTokenProvider(SysConfigRepository sysConfig, WaizaoProperties props) {
        this.sysConfig = sysConfig;
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
        String persisted = sysConfig == null ? null : sysConfig.waizaoToken();
        if (StringUtils.hasText(persisted)) {
            return persisted.trim();
        }
        String fallback = props.getApi() == null ? null : props.getApi().getToken();
        return fallback == null ? "" : fallback.trim();
    }
}
