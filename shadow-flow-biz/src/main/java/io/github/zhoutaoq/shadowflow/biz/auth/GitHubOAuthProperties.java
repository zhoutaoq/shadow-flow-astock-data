package io.github.zhoutaoq.shadowflow.biz.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * GitHub OAuth 三方登录参数（{@code app.auth.github.*}）。
 *
 * <p>凭据不写进本文件也不写进 application.yml —— 真实值放 classpath 的 {@code app.env}
 * （不入库），yml 里只留 {@code ${GITHUB_CLIENT_ID:}} 占位符，与数据库口令、超管口令同一套规矩。
 *
 * <p><b>是否启用不看开关看凭据</b>：{@code enabled} 只是总闸，client-id / client-secret
 * 有一个没配就视为未开通 —— 前端不显示 GitHub 按钮，接口直接拒绝。
 * 部署环境不想让 GitHub 登录生效，把 enabled 拨到 false 即可，不用动凭据。
 *
 * <p>web-base-url / api-base-url 留默认就是 github.com 公有云；
 * GitHub Enterprise 换成自己的域名即可，流程代码不用改。
 */
@Component
@ConfigurationProperties(prefix = "app.auth.github")
public class GitHubOAuthProperties {

    /** 总开关 */
    private boolean enabled = true;
    /** OAuth App 的 Client ID（公开值，但按惯例与 secret 一起放 app.env） */
    private String clientId = "";
    /** OAuth App 的 Client Secret（绝不入库） */
    private String clientSecret = "";
    /**
     * 回调地址。留空 = 按当前请求动态推导（{@code <当前站点>/api/auth/github/callback}）。
     *
     * <p>GitHub 后台登记的 Authorization callback URL 必须与实际请求的 redirect_uri 逐字符一致，
     * 反向代理后推导出的 scheme/host 可能不对，这时在配置里写死绝对地址。
     */
    private String redirectUri = "";
    /** 申请的授权范围：拿用户资料 + 邮箱（邮箱用于匹配/建号） */
    private String scope = "read:user user:email";
    /** 站点地址（授权跳转） */
    private String webBaseUrl = "https://github.com";
    /** API 地址（拉资料） */
    private String apiBaseUrl = "https://api.github.com";
    /**
     * 换 token 的地址前缀，留空 = 跟 {@link #webBaseUrl} 一致（即 {@code https://github.com}）。
     *
     * <p>单独拆出来是因为这一步走的是「服务器 → GitHub」，和用户浏览器无关：
     * 服务器到 github.com 的链路会成块抖动，必要时把它指到自建中转（反代 github.com 的 Worker），
     * 而授权页跳转仍走真正的 github.com。
     */
    private String tokenBaseUrl = "";
    /** 连接超时：短一点，失败得快才有机会多试几次 */
    private int connectTimeoutMs = 5000;
    /** 读超时：TLS 已握手、只是响应慢时用 */
    private int readTimeoutMs = 7000;

    /** 真的能用：总闸开 + 凭据齐 */
    public boolean isConfigured() {
        return enabled && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public String getWebBaseUrl() {
        return webBaseUrl;
    }

    public void setWebBaseUrl(String webBaseUrl) {
        this.webBaseUrl = webBaseUrl;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    public void setApiBaseUrl(String apiBaseUrl) {
        this.apiBaseUrl = apiBaseUrl;
    }

    /** 换 token 用的前缀：配了 token-base-url 就用它，否则跟 web-base-url */
    public String exchangeBaseUrl() {
        return StringUtils.hasText(tokenBaseUrl) ? tokenBaseUrl.trim() : webBaseUrl;
    }

    public String getTokenBaseUrl() {
        return tokenBaseUrl;
    }

    public void setTokenBaseUrl(String tokenBaseUrl) {
        this.tokenBaseUrl = tokenBaseUrl;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
