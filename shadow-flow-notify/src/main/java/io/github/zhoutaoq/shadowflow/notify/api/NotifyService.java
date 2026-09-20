package io.github.zhoutaoq.shadowflow.notify.api;

import io.github.zhoutaoq.shadowflow.notify.config.NotifyProperties;
import io.github.zhoutaoq.shadowflow.notify.support.NotifyText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通知门面：调用方唯一的入口。
 *
 * <p>调用方只需要「给我一条消息」，剩下的交给它：
 * <pre>
 * 总开关 → 逐个闸门（静默时段、调用方自定义的规则）→ 解析目标地址
 *        → 按 channel 路由到通道实现 → 投递
 * </pre>
 *
 * <p><b>为什么把这一串收在一个类里</b>：改造前每个通知方各写一遍「先看开关、再看时段、
 * 再取地址、再拼 JSON」，五处实现五套口径，任何一处漏了开关就是线上事故。
 * 收口之后，「发不出去」只可能有一个原因链条，且每一步都写了日志。
 *
 * <p>本类<b>不生成、不改写任何业务文本</b>，也不认识「盯盘」「注册」这些概念
 * —— 它只认消息里的字段。
 */
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    private final NotifyProperties props;
    private final NotifyTargetResolver resolver;
    private final List<NotifyGate> gates;
    private final Map<String, Notifier> notifiers;

    /**
     * @param props     通用配置，可为 null（按默认值工作）
     * @param resolver  目标解析器，可为 null（按「target 就是地址」处理）
     * @param gates     闸门列表，可为 null/空（不做任何拦截）
     * @param notifiers 通道实现列表，可为 null/空（此时全部发送失败并记日志）
     */
    public NotifyService(NotifyProperties props, NotifyTargetResolver resolver,
                         List<NotifyGate> gates, List<Notifier> notifiers) {
        this.props = props == null ? new NotifyProperties() : props;
        this.resolver = resolver == null ? NotifyTargetResolver.identity() : resolver;
        this.gates = gates == null ? Collections.<NotifyGate>emptyList() : gates;
        Map<String, Notifier> map = new LinkedHashMap<>();
        if (notifiers != null) {
            for (Notifier n : notifiers) {
                if (n != null && !NotifyText.blank(n.channel())) {
                    map.put(n.channel().trim().toLowerCase(), n);
                }
            }
        }
        this.notifiers = Collections.unmodifiableMap(map);
        log.info("[notify] 通知门面已就绪：总开关={}，通道={}，闸门={} 个，允许时段={}（启用={}）",
                props.isEnabled(), this.notifiers.keySet(), this.gates.size(),
                NotifyText.blank(props.getAllowedWindows()) ? "(不限)" : props.getAllowedWindows(),
                props.isEnforceAllowedWindows());
    }

    /**
     * 投递一条消息。
     *
     * @return 是否投递成功；任何一步被拦住/失败都会返回 false 并写日志，
     *         但<b>不会抛异常</b> —— 通知失败不该影响调用方的主流程
     */
    public boolean send(NotifyMessage message) {
        if (message == null) {
            return false;
        }
        if (!props.isEnabled()) {
            log.info("[notify] 总开关已关闭，本次不发送：{}", NotifyText.oneLine(message.render()));
            return false;
        }
        for (NotifyGate gate : gates) {
            String reason;
            try {
                reason = gate.blockReason(message);
            } catch (RuntimeException e) {
                // 闸门自己出错时选择放行：宁可多发一条，也不要因为闸门 bug 把通知全堵死
                log.warn("[notify] 闸门 {} 判断异常，本次按放行处理：{}",
                        gate.getClass().getSimpleName(), e.getMessage());
                continue;
            }
            if (reason != null) {
                log.info("[notify] 被拦截（{}），本次不发送：{}",
                        reason, NotifyText.oneLine(message.render()));
                return false;
            }
        }

        String channel = NotifyText.blank(message.channel())
                ? props.getDefaultChannel() : message.channel().trim().toLowerCase();
        String endpoint;
        try {
            endpoint = resolver.resolve(channel, message.target());
        } catch (RuntimeException e) {
            log.error("[notify] 解析目标地址失败（{} <- {}）: {}",
                    channel, message.target(), e.getMessage());
            return false;
        }
        if (NotifyText.blank(endpoint)) {
            log.warn("[notify] 没有可用的投递地址（通道 {}，目标 {}），本次不发送：{}",
                    channel, message.target(), NotifyText.oneLine(message.render()));
            return false;
        }
        Notifier notifier = notifiers.get(channel);
        if (notifier == null) {
            log.error("[notify] 没有注册通道实现：{}（已注册：{}），本次不发送",
                    channel, notifiers.keySet());
            return false;
        }
        NotifyResult result = notifier.send(endpoint, message);
        if (!result.isOk()) {
            log.warn("[notify] 投递未成功（{} -> {}）：{}；内容摘要：{}",
                    channel, NotifyText.maskEndpoint(endpoint), result.detail(),
                    NotifyText.oneLine(message.render()));
        }
        return result.isOk();
    }

    /** 便捷入口：纯文本 + 逐字前缀（前缀为空则不加）到指定目标 */
    public boolean send(String target, String prefix, String content) {
        return send(NotifyMessage.to(target).prefix(prefix).content(content).build());
    }

    /** 便捷入口：纯文本到指定目标，不加前缀 */
    public boolean send(String target, String content) {
        return send(target, null, content);
    }

    /**
     * 便捷入口：不受静默时段限制的文本消息（仍受总开关与其它闸门约束）。
     *
     * <p>用于「人触发的独立事件」：什么时候发生就什么时候该被通知。
     */
    public boolean sendUrgent(String target, String prefix, String content) {
        return send(NotifyMessage.to(target).prefix(prefix).content(content)
                .bypassSchedule().build());
    }

    /** 已注册的通道标识，供启动日志与排查用 */
    public java.util.Set<String> channels() {
        return notifiers.keySet();
    }
}
