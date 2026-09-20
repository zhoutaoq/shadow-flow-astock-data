package io.github.zhoutaoq.shadowflow.biz.auth;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.config.IntegrationConfigService;
import io.github.zhoutaoq.shadowflow.biz.watch.DingTalkNotifier;
import io.github.zhoutaoq.shadowflow.biz.watch.TradeSessions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 注册埋点：新用户注册成功时推一条钉钉，内容是<b>注册时间 + 邮箱</b>。
 *
 * <p>消息形态（首行带关键词，钉钉「自定义关键词」模式必须命中才放行）：
 * <pre>
 * [智能盯盘] 新用户注册
 * 时间：2026-09-16 22:51:38
 * 邮箱：someone@example.com
 * </pre>
 *
 * <p>三条刻意的设计，都是踩过坑才这么定的：
 * <ol>
 *   <li><b>不看交易时段</b>（走 {@link DingTalkNotifier#postAlways}）。注册是全天候的人触发事件，
 *       而钉钉那边平时挂着「非交易时段一律静默」的闸门（治盯盘轮询误报的）。
 *       注册高峰常在晚上，走普通出口会被静默掉、埋点等于没做。</li>
 *   <li><b>异步发送</b>。注册必须秒回：钉钉超时上限是连接 5s + 读 10s，同步发就等于把
 *       这十几秒挂在用户的注册请求上。这里用一个单线程 daemon 池发，
 *       进程退出时丢掉未发出的消息是可以接受的（通知不是账本）。</li>
 *   <li><b>吞掉一切异常</b>。推送失败绝不能让 {@code register()} 抛出去 ——
 *       用户已经建好了，不能因为「通知没发出去」就告诉他注册失败。失败只在 app.log 留痕。</li>
 * </ol>
 *
 * <p>邮箱就是用户名：本项目的用户名规则本身就要求是合法邮箱（见 {@link AuthService#checkUsername}），
 * 所以不需要另取字段。
 *
 * <p><b>v1.5.9</b>：机器人地址与关键词都改从系统配置页取（{@link IntegrationConfigService}），
 * 不再读 {@code watch.dingtalk.webhook} 这个 bean 字段 —— 换机器人、改关键词不用重启。
 */
@Service
public class RegistrationNotifier {

    private static final Logger log = LoggerFactory.getLogger(RegistrationNotifier.class);

    /** 时间戳口径与项目其它模块一致；取的是<b>注册那一刻</b>（北京时间） */
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RegistrationNotifyProperties props;
    private final IntegrationConfigService integration;
    private final DingTalkNotifier notifier;

    private final ExecutorService executor;

    public RegistrationNotifier(RegistrationNotifyProperties props,
                                IntegrationConfigService integration,
                                DingTalkNotifier notifier) {
        this.props = props;
        this.integration = integration;
        this.notifier = notifier;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "register-notify");
            // daemon：不拦着 JVM 退出。注册通知没发完就停机，丢掉一条是可以接受的
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 用户已落库之后调它。立即返回，实际推送在后台线程完成。
     *
     * @param u 刚注册出来的用户；为 null 时直接跳过（理论上不会）
     */
    public void notifyRegistered(User u) {
        if (u == null) {
            return;
        }
        if (!props.isEnabled()) {
            log.info("[register] 注册埋点已关闭（waizao.auth.notify.enabled=false），不推送：{}", u.getUsername());
            return;
        }
        // 时间在「注册这一刻」取，不在后台线程里取 ——
        // 万一推送排队了，消息里的时间仍然是真的注册时间
        final String email = u.getUsername() == null ? "" : u.getUsername();
        final String when = TradeSessions.nowCn().format(TS);
        executor.execute(() -> push(email, when));
    }

    /** 真正发送。这里已经不在请求线程上了，任何异常都只记日志 */
    private void push(String email, String when) {
        try {
            // webhook 在「发的那一刻」取：运维在系统配置页换了机器人，注册埋点跟着走，
            // 不需要重启（和盯盘告警读的是同一只）
            String webhook = integration.dingtalkWebhook();
            if (webhook == null || webhook.isEmpty()) {
                log.warn("[register] 钉钉未配置 webhook（系统配置页未填、yml 兜底也为空），注册埋点只留在日志里：{}", email);
                return;
            }
            if (notifier.postAlways(webhook, buildText(email, when))) {
                log.info("[register] 注册埋点已推送：{} {}", when, email);
            } else {
                // postAlways 返回 false 只有两种可能：总开关关着，或 webhook 为空（上面已排除），
                // 两种原因它自己都打了日志，这里只补一句「这条没送出去」
                log.warn("[register] 注册埋点未能送出：{}", email);
            }
        } catch (Exception e) {
            log.error("[register] 注册埋点推送异常（不影响注册）：{}", e.getMessage(), e);
        }
    }

    /** 拼钉钉文本：首行带关键词前缀，后面固定两行 —— 时间、邮箱 */
    private String buildText(String email, String when) {
        String keyword = integration.registerKeyword();
        StringBuilder sb = new StringBuilder();
        sb.append(keyword.isEmpty() ? "" : "[" + keyword + "] ").append("新用户注册\n");
        sb.append("时间：").append(when).append('\n');
        sb.append("邮箱：").append(email);
        return sb.toString();
    }
}
