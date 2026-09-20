package io.github.zhoutaoq.shadowflow.biz.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 注册埋点的配置，绑定前缀 <code>waizao.auth.notify</code>。
 *
 * <p>新用户注册成功时推一条钉钉（<b>注册时间 + 邮箱</b>），用的是<b>系统钉钉配置</b>
 * （{@code watch.dingtalk.webhook}，也就是盯盘页面「实时通知配置」里那个），
 * 所以这里没有 webhook 字段 —— 不另拉群、不用重复配机器人。
 *
 * <p>与 {@link AuthProperties}（前缀 {@code app.auth}）分开：那个管的是登录态时长与注册开关，
 * 这个只管「注册完要不要吱一声」。
 */
@Component
@ConfigurationProperties(prefix = "waizao.auth.notify")
public class RegistrationNotifyProperties {

    /** 埋点总开关：false 时注册不再推送（注册本身完全不受影响，只是不通知） */
    private boolean enabled = true;

    /**
     * 钉钉机器人「自定义关键词」模式下必须命中的关键词。
     *
     * <p>钉钉只在消息文本里能搜到该关键词时才放行，所以消息首行会拼成
     * <code>[关键词] 新用户注册</code>。复用盯盘机器人时保持与它一致（智能盯盘）即可，
     * 换成别的机器人就改成对方后台配的那个词。
     */
    private String keyword = "智能盯盘";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword;
    }
}
