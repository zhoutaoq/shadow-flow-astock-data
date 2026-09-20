package io.github.zhoutaoq.shadowflow.notify.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一条待投递的通知。
 *
 * <p>本类只描述「投什么、投给谁」，不关心怎么投 —— 投递细节在
 * {@link Notifier} 实现里，目标地址解析在 {@link NotifyTargetResolver} 里。
 *
 * <p><b>与业务无关</b>：这里的每个字段都是通用概念。业务文案（盯盘告警、注册通知、
 * 用户反馈……）由调用方拼好后放进 {@link #content()}，SDK 不生成也不改写任何业务文本。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #channel()} —— 通道标识（如 {@code dingtalk}）。留空表示用配置里的默认通道。</li>
 *   <li>{@link #target()} —— 目标。<b>既可以是逻辑键</b>（如 {@code system}），
 *       由 {@link NotifyTargetResolver} 解析成真实地址；<b>也可以直接是地址本身</b>
 *       （解析器认不出来时的兜底行为就是原样返回）。</li>
 *   <li>{@link #prefix()} —— 逐字前缀，渲染时拼在正文之前。典型用途是满足机器人
 *       「自定义关键词」校验的标签 + 发送时刻，例如 <code>[标签-主机] 2026-09-20 13:20:00</code>。
 *       标签文字由调用方决定，SDK 不认识它。</li>
 *   <li>{@link #markdown()} —— 正文格式；false 为纯文本。</li>
 *   <li>{@link #bypassSchedule()} —— 是否跳过「静默时段」闸门。
 *       语义是「这条消息不受时段限制」，<b>不跳过</b>总开关与其它闸门。</li>
 * </ul>
 */
public final class NotifyMessage {

    private final String channel;
    private final String target;
    private final String content;
    private final String prefix;
    private final boolean markdown;
    private final boolean bypassSchedule;
    private final Map<String, String> meta;

    private NotifyMessage(Builder b) {
        this.channel = b.channel;
        this.target = b.target;
        this.content = b.content;
        this.prefix = b.prefix;
        this.markdown = b.markdown;
        this.bypassSchedule = b.bypassSchedule;
        this.meta = b.meta;
    }

    public static Builder to(String target) {
        return new Builder(target);
    }

    public String channel() {
        return channel;
    }

    public String target() {
        return target;
    }

    public String content() {
        return content;
    }

    public String prefix() {
        return prefix;
    }

    public boolean markdown() {
        return markdown;
    }

    public boolean bypassSchedule() {
        return bypassSchedule;
    }

    /** 调用方自定义的附加信息（透传，SDK 不解释）；无附加信息时返回空 Map */
    public Map<String, String> meta() {
        return meta;
    }

    /** 最终投递的文本：前缀 + 单个空格 + 正文（前缀为空时就是正文本身） */
    public String render() {
        if (prefix == null || prefix.trim().isEmpty()) {
            return content == null ? "" : content;
        }
        return prefix + " " + (content == null ? "" : content);
    }

    public static final class Builder {
        private String channel;
        private String target;
        private String content;
        private String prefix;
        private boolean markdown;
        private boolean bypassSchedule;
        private Map<String, String> meta;

        private Builder(String target) {
            this.target = target;
        }

        public Builder channel(String channel) {
            this.channel = channel;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        /** 逐字前缀；为 null/空白时不加前缀 */
        public Builder prefix(String prefix) {
            this.prefix = prefix;
            return this;
        }

        /** 用 markdown 正文格式 */
        public Builder markdown() {
            this.markdown = true;
            return this;
        }

        /** 本条消息跳过静默时段（仍受总开关约束） */
        public Builder bypassSchedule() {
            this.bypassSchedule = true;
            return this;
        }

        public Builder meta(String key, String value) {
            if (key == null) {
                return this;
            }
            if (this.meta == null) {
                this.meta = new LinkedHashMap<>();
            }
            this.meta.put(key, value);
            return this;
        }

        public NotifyMessage build() {
            return new NotifyMessage(this);
        }
    }
}
