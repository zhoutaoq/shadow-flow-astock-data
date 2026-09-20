package io.github.zhoutaoq.shadowflow.notify.config;

import io.github.zhoutaoq.shadowflow.notify.api.Notifier;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyGate;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyService;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyTargetResolver;
import io.github.zhoutaoq.shadowflow.notify.channel.dingtalk.DingTalkWebhookNotifier;
import io.github.zhoutaoq.shadowflow.notify.policy.AllowedWindowsGate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.ArrayList;
import java.util.List;

/**
 * 通知 SDK 的自动装配。
 *
 * <p>引入依赖即可用，不需要在业务工程里写任何声明式配置：
 * 本类会装配门面、内置通道与内置闸门；业务侧只要按需提供
 * {@link NotifyTargetResolver}（目标从哪来）与额外的 {@link NotifyGate}
 * （还有哪些规则要拦），两者都是 {@code @ConditionalOnMissingBean}，
 * 不提供时分别退化成「target 就是地址」与「不额外拦截」。
 *
 * <p>业务侧再注册更多 {@link Notifier} 即可扩展通道，无需改本类。
 */
@AutoConfiguration
@ConditionalOnClass(NotifyService.class)
@EnableConfigurationProperties(NotifyProperties.class)
public class ShadowFlowNotifyAutoConfiguration {

    /** 目标解析：业务没提供时，按「传进来的 target 就是地址本身」处理 */
    @Bean
    @ConditionalOnMissingBean
    public NotifyTargetResolver notifyTargetResolver() {
        return NotifyTargetResolver.identity();
    }

    /** 内置通道：群机器人 Webhook */
    @Bean
    @ConditionalOnMissingBean(name = "dingTalkWebhookNotifier")
    public Notifier dingTalkWebhookNotifier(NotifyProperties props) {
        return new DingTalkWebhookNotifier(props);
    }

    /** 内置闸门：允许发送时段（未配时段时不起作用） */
    @Bean
    @ConditionalOnMissingBean(name = "allowedWindowsGate")
    public NotifyGate allowedWindowsGate(NotifyProperties props) {
        return new AllowedWindowsGate(props);
    }

    /**
     * 门面：把配置、解析器、全部闸门与全部通道实现收集起来。
     *
     * <p>用 {@link ObjectProvider} 而不是直接注入 {@code List}，是为了让业务侧的
     * 自定义闸门/通道也能被自动收进来（同一类型多 Bean 时按声明顺序）。
     */
    @Bean
    @ConditionalOnMissingBean
    public NotifyService notifyService(NotifyProperties props,
                                       NotifyTargetResolver resolver,
                                       ObjectProvider<NotifyGate> gates,
                                       ObjectProvider<Notifier> notifiers) {
        List<NotifyGate> gateList = new ArrayList<>();
        gates.forEach(gateList::add);
        List<Notifier> notifierList = new ArrayList<>();
        notifiers.forEach(notifierList::add);
        return new NotifyService(props, resolver, gateList, notifierList);
    }
}
