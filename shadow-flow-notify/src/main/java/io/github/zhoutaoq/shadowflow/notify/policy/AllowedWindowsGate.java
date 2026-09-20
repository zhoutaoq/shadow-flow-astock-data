package io.github.zhoutaoq.shadowflow.notify.policy;

import io.github.zhoutaoq.shadowflow.notify.api.NotifyGate;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyMessage;
import io.github.zhoutaoq.shadowflow.notify.config.NotifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.DayOfWeek;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 内置闸门：只允许在配置的时段内发送（通用「静默时段」语义）。
 *
 * <p>用途是压掉「机器轮询产生的噪声」—— 值守窗口之外，轮询仍在跑，
 * 但它产出的消息往往没有信息量，还不如不发。
 *
 * <p>标记了 {@link NotifyMessage#bypassSchedule()} 的消息会跳过本闸门：
 * 那类是「人触发的独立事件」（有人提交了、有人注册了），
 * 它发生的时间就是它该被通知的时间，没有「时段」可言。
 * <b>跳过的只是本闸门，总开关与其它闸门仍然生效。</b>
 */
public class AllowedWindowsGate implements NotifyGate {

    private static final Logger log = LoggerFactory.getLogger(AllowedWindowsGate.class);

    private final NotifyProperties props;
    /** 配置写坏时只告警一次，别每轮刷日志 */
    private final AtomicBoolean warned = new AtomicBoolean(false);

    public AllowedWindowsGate(NotifyProperties props) {
        this.props = props;
    }

    @Override
    public String blockReason(NotifyMessage message) {
        if (props == null || !props.isEnforceAllowedWindows()) {
            return null;
        }
        if (message != null && message.bypassSchedule()) {
            return null;
        }
        Set<DayOfWeek> days = AllowedWindows.parseDays(props.getDaysOfWeek());
        if (!AllowedWindows.inNow(props.getAllowedWindows(), days, props.zone())) {
            return "不在允许发送的时段内（" + props.getAllowedWindows() + "）";
        }
        if (!warned.getAndSet(true) && !AllowedWindows.parseToText(props.getAllowedWindows()).isEmpty()) {
            log.info("[notify] 允许发送时段已生效：{}（星期 {}，时区 {}）",
                    props.getAllowedWindows(), props.getDaysOfWeek(), props.zone());
        }
        return null;
    }
}
