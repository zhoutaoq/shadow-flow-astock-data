package io.github.zhoutaoq.shadowflow.biz.feedback;

import io.github.zhoutaoq.shadowflow.biz.config.IntegrationConfigService;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 问题反馈：校验 → 限流 → 拼消息 → 推钉钉。
 *
 * <p>反馈<b>不落库</b>（无数据库模式没有表），所以「留痕」靠两处：
 * 服务端 INFO 日志（<code>app.log</code> 里可回溯）+ 钉钉群里那条消息。
 *
 * <p>消息固定四段，前三段由服务端生成，用户只能左右最后一段：
 * <pre>
 * [智能盯盘] 问题反馈
 * IP：1.2.3.4
 * 时间：2026-09-11 14:45:12
 * 内容：
 * 这里是用户填的内容
 * </pre>
 *
 * <p>失败一律抛 {@link IllegalArgumentException}：本项目的
 * {@code GlobalExceptionHandler} 会把它转成 {@code {success:false,message}} 给前端，
 * 页面直接 toast 出来，不用各写一套错误码。
 */
@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    /** 冷却表的清理阈值：条目超过这个数就来一轮过期清理，避免长期运行后无限增长 */
    private static final int COOLDOWN_SWEEP_SIZE = 1024;
    /** 冷却条目保留多久（毫秒）：超过它就没必要再记着了 */
    private static final long COOLDOWN_KEEP_MS = 60 * 60 * 1000L;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FeedbackProperties props;
    private final IntegrationConfigService integration;
    private final NotifyService notify;

    /** IP → 最近一次提交时间戳（毫秒） */
    private final Map<String, Long> lastSubmitAt = new ConcurrentHashMap<>();

    public FeedbackService(FeedbackProperties props, IntegrationConfigService integration,
                           NotifyService notify) {
        this.props = props;
        this.integration = integration;
        this.notify = notify;
    }

    /**
     * 提交一条反馈并推钉钉。
     *
     * @param rawContent 用户填的原始内容（可为 null）
     * @param ip         客户端 IP，由 controller 从请求里取
     * @throws IllegalArgumentException 入口关闭 / 内容为空或超长 / 提交过于频繁 / 钉钉推送失败
     */
    public void submit(String rawContent, String ip) {
        if (!props.isEnabled()) {
            throw new IllegalArgumentException("反馈入口已关闭，请联系管理员");
        }

        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }
        int max = props.getMaxLength();
        if (max > 0 && content.length() > max) {
            throw new IllegalArgumentException("反馈内容最多 " + max + " 字，当前 "
                    + content.length() + " 字，请精简后再提交");
        }

        String who = ip == null || ip.trim().isEmpty() ? "未知" : ip.trim();
        String when = LocalDateTime.now().format(TS);
        checkCooldown(who);

        // 先落日志：即使钉钉推送失败，服务端也留下了这条反馈
        log.info("[feedback] 收到反馈 IP={} 时间={} 内容={}", who, when, content.replace("\n", " ⏎ "));

        String webhook = resolveWebhook();
        if (webhook == null) {
            log.warn("[feedback] 没有可用的钉钉 webhook（反馈专用与钉钉机器人都没配），本条只留在日志里");
            throw new IllegalArgumentException("反馈暂不能送达，请稍后再试");
        }

        // 正文自己带关键词首行与 IP / 时间，所以不再额外加前缀
        if (!notify.send(webhook, null, buildText(content, who, when))) {
            throw new IllegalArgumentException("反馈没能送出（钉钉推送失败），请稍后再试");
        }
        lastSubmitAt.put(who, System.currentTimeMillis());
    }

    /**
     * 前端要用的限制参数（页面拿它设置输入框上限、提示文案）。
     *
     * <p>不是为了"安全"——真正的校验在 {@link #submit} 里，后端才是唯一权威；
     * 这里只是让前端不必把 500 / 30 这些数字再抄一份、改配置时不至于两边不一致。
     */
    public Map<String, Object> limits() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", props.isEnabled());
        m.put("maxLength", props.getMaxLength());
        m.put("cooldownSeconds", props.getCooldownSeconds());
        return m;
    }

    /**
     * 实际使用的 webhook：反馈专用那只优先，没单独配就复用钉钉机器人
     * （这样反馈和盯盘告警进同一个群，不用重复开一只机器人）。
     *
     * <p>两处的值都从系统配置页取、以 application.yml 兜底（v1.5.9），
     * 这一层只负责「谁优先」，不再自己判断配置来源。
     *
     * @return 可用的 webhook，两处都没配时返回 null
     */
    private String resolveWebhook() {
        String webhook = integration.feedbackWebhook();
        return webhook == null || webhook.isEmpty() ? null : webhook;
    }

    /** 拼钉钉文本：首行带关键词前缀（机器人「自定义关键词」模式必须命中才放行） */
    private String buildText(String content, String ip, String when) {
        String keyword = integration.feedbackKeyword();
        StringBuilder sb = new StringBuilder();
        sb.append(keyword.isEmpty() ? "" : "[" + keyword + "] ").append("问题反馈\n");
        sb.append("IP：").append(ip).append('\n');
        sb.append("时间：").append(when).append('\n');
        sb.append("内容：\n").append(content);
        return sb.toString();
    }

    /**
     * 同一 IP 的提交冷却：间隔小于配置值时拒绝。
     *
     * <p>只记「成功推出去」的时间（失败不占用冷却），否则用户被限流后连重试都做不了。
     */
    private void checkCooldown(String ip) {
        long cd = props.getCooldownSeconds() * 1000L;
        if (cd <= 0) {
            return;
        }
        Long last = lastSubmitAt.get(ip);
        if (last != null && System.currentTimeMillis() - last < cd) {
            long wait = (cd - (System.currentTimeMillis() - last) + 999) / 1000;
            throw new IllegalArgumentException("提交太频繁，请 " + wait + " 秒后再试");
        }
        if (lastSubmitAt.size() > COOLDOWN_SWEEP_SIZE) {
            sweepExpired();
        }
    }

    /** 清掉过期的冷却记录，防止 IP 多了以后这个 map 只涨不跌 */
    private void sweepExpired() {
        long deadline = System.currentTimeMillis() - COOLDOWN_KEEP_MS;
        Iterator<Map.Entry<String, Long>> it = lastSubmitAt.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < deadline) {
                it.remove();
            }
        }
    }
}
