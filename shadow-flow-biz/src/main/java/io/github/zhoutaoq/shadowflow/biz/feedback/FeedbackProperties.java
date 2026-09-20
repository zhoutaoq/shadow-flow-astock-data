package io.github.zhoutaoq.shadowflow.biz.feedback;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 用户反馈的配置，绑定前缀 <code>feedback</code>。
 *
 * <p>反馈本身不落库（无数据库模式也没有表），提交后只做一件事：拼一条消息推钉钉。
 * 所以这里只有"推给谁、能写多长、多久能提一次"三类参数。
 */
@Component
@ConfigurationProperties(prefix = "feedback")
public class FeedbackProperties {

    /** 总开关：false 时接口直接拒绝，页面提交会拿到"反馈入口已关闭" */
    private boolean enabled = true;

    /**
     * 钉钉机器人 Webhook（含 access_token）。
     *
     * <p><b>留空 = 复用盯盘的机器人</b>（<code>watch.dingtalk.webhook</code>），
     * 即反馈和盯盘告警进同一个群，开箱即用；要单独拉一个群只放反馈就填这里。
     */
    private String webhook = "";

    /**
     * 钉钉机器人「自定义关键词」模式下必须命中的关键词。
     *
     * <p>钉钉只在消息文本里能搜到该关键词时才放行，所以消息第一行会拼成
     * <code>[关键词] ...</code>。复用盯盘的机器人时保持与盯盘一致（智能盯盘）即可，
     * 换成别的机器人时改成对方后台配的那个词。
     */
    private String keyword = "智能盯盘";

    /** 单条反馈的内容上限（字符）：钉钉文本消息整体也有 20000 字节上限，这里收紧得多 */
    private int maxLength = 500;

    /** 同一个 IP 两次提交之间的最小间隔（秒）：防止误连点或恶意刷屏 */
    private int cooldownSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWebhook() {
        return webhook;
    }

    public void setWebhook(String webhook) {
        this.webhook = webhook == null ? "" : webhook;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword == null ? "" : keyword;
    }

    public int getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(int maxLength) {
        this.maxLength = maxLength;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    public void setCooldownSeconds(int cooldownSeconds) {
        this.cooldownSeconds = cooldownSeconds;
    }

    /** 本配置里是否单独指定了 webhook（留空表示复用盯盘机器人） */
    public boolean webhookConfigured() {
        return webhook != null && !webhook.trim().isEmpty();
    }
}
