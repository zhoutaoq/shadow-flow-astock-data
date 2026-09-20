package io.github.zhoutaoq.shadowflow.biz.config;

import io.github.zhoutaoq.shadowflow.biz.support.PushEnvelope;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyMessage;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyTargetResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 把通知 SDK 接到本系统的配置源上。
 *
 * <p>SDK 侧只认「通道 + 目标地址」，本类负责回答「目标从哪来」：
 * <ul>
 *   <li>逻辑目标键 {@link #TARGET_SYSTEM} —— <b>系统通知机器人</b>：平台自己发通知的出口
 *       （发布通知 / 注册通知 / 反馈兜底），兼作盯盘的默认群。
 *       它的地址由超管在「系统配置」页维护，<b>每次发送都现取</b>，所以换机器人不用重启。</li>
 *   <li>其它目标 —— 一律当作地址本身。用户的盯盘机器人地址就存在各自的盯盘配置里，
 *       这里原样放行（SDK 的解析器约定：认不出来就原样返回）。</li>
 * </ul>
 *
 * <p><b>刻意不在本类做闸门判断</b>：能不能发、什么时段能发，都由配置驱动
 * （{@code shadowflow.notify.enabled} / {@code allowed-windows}）交给 SDK 统一执行，
 * 避免「闸门散在业务代码里、漏一处就是线上事故」。
 */
@Configuration
public class NotifyWiring {

    private static final Logger log = LoggerFactory.getLogger(NotifyWiring.class);

    /** 逻辑目标键：系统通知机器人（不是地址，由 {@link IntegrationConfigService} 解析成真实地址） */
    public static final String TARGET_SYSTEM = "system";

    /** 旧的总开关键，v1.6.0 起由 shadowflow.notify.enabled 取代 */
    private static final String LEGACY_ENABLED_KEY = "watch.dingtalk.enabled";

    public NotifyWiring(Environment env, @Value("${shadowflow.notify.enabled:true}") boolean notifyEnabled) {
        // 启动时把公网 IP 预热一下（要发外部请求，走后台线程不阻塞启动），
        // 这样第一条外发消息拼前缀时通常已有缓存，不必同步等网络。
        PushEnvelope.warmUp();
        if (!notifyEnabled) {
            log.info("[notify] 通知总开关已关闭（shadowflow.notify.enabled=false），任何通道都不会发送");
        }
        // 迁移护栏：老启动参数 --watch.dingtalk.enabled=true 在 v1.6.0 已失效。
        // 若不提示，发布后会是「所有通知静默」这种最难查的故障 —— 请求照常成功、日志也没有异常。
        if (env != null && env.containsProperty(LEGACY_ENABLED_KEY)) {
            log.warn("[notify] 检测到已废弃的启动参数 --{}=...，v1.6.0 起不再生效；"
                            + "请改用 --shadowflow.notify.enabled=true（见 sh/server.env 的 ARGS）",
                    LEGACY_ENABLED_KEY);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    public NotifyTargetResolver notifyTargetResolver(IntegrationConfigService integration) {
        return (channel, target) -> {
            if (target == null) {
                return "";
            }
            if (TARGET_SYSTEM.equals(target.trim())) {
                return integration.dingtalkWebhook();
            }
            return target;
        };
    }
}
