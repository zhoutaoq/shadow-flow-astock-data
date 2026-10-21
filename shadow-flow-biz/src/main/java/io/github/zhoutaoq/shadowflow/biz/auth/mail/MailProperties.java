package io.github.zhoutaoq.shadowflow.biz.auth.mail;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 邮箱验证码相关的全部参数（{@code app.mail.*}）。
 *
 * <p><b>凭据不进这个文件也不进 application.yml</b>：{@code username} / {@code password}
 * 在 yml 里只是 {@code ${MAIL_USERNAME:}} / {@code ${MAIL_PASSWORD:}} 占位符，
 * 真实值由部署机器的 app.env 提供（QQ 邮箱填的是「授权码」，不是邮箱登录密码）。
 *
 * <p>「配没配」是唯一判据：账号或口令任一为空即视为未开通 —— 这时注册与邮箱登录
 * 会明确报「邮件服务未配置」，而不是发不出去还装作成功。超管 admin（用户名不是邮箱）
 * 与 GitHub 三方登录不受影响。
 */
@Component
@ConfigurationProperties(prefix = "app.mail")
public class MailProperties {

    /** 总开关：false 时不发信、也不校验验证码（只用于本地没有 SMTP 的排障场景） */
    private boolean enabled = true;

    /** SMTP 服务器，QQ 邮箱为 smtp.qq.com */
    private String host = "smtp.qq.com";

    /** SMTP 端口，SSL 一般是 465、STARTTLS 是 587 */
    private int port = 465;

    /** 是否用 SSL（QQ / 163 都是 465 + SSL） */
    private boolean ssl = true;

    /** 发信账号，如 gdev.online@qq.com；来自 app.env 的 MAIL_USERNAME */
    private String username = "";

    /** 发信口令（QQ 邮箱是授权码）；来自 app.env 的 MAIL_PASSWORD */
    private String password = "";

    /** 发件人显示名 */
    private String fromName = "影流数据";

    /** 验证码有效期（分钟） */
    private int codeTtlMinutes = 10;

    /** 同一邮箱两次发码的最小间隔（秒），页面倒计时读它 */
    private int resendSeconds = 60;

    /** 同一邮箱 24 小时内最多发几条 */
    private int dailyLimit = 20;

    /** 同一条验证码最多试错几次，超过即作废 */
    private int maxAttempts = 5;

    /** 注册是否必须验证码 */
    private boolean requireOnRegister = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public boolean isSsl() {
        return ssl;
    }

    public void setSsl(boolean ssl) {
        this.ssl = ssl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFromName() {
        return fromName;
    }

    public void setFromName(String fromName) {
        this.fromName = fromName;
    }

    public int getCodeTtlMinutes() {
        return codeTtlMinutes;
    }

    public void setCodeTtlMinutes(int codeTtlMinutes) {
        this.codeTtlMinutes = codeTtlMinutes;
    }

    public int getResendSeconds() {
        return resendSeconds;
    }

    public void setResendSeconds(int resendSeconds) {
        this.resendSeconds = resendSeconds;
    }

    public int getDailyLimit() {
        return dailyLimit;
    }

    public void setDailyLimit(int dailyLimit) {
        this.dailyLimit = dailyLimit;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public boolean isRequireOnRegister() {
        return requireOnRegister;
    }

    public void setRequireOnRegister(boolean requireOnRegister) {
        this.requireOnRegister = requireOnRegister;
    }
}
