package io.github.zhoutaoq.shadowflow.biz.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.zhoutaoq.shadowflow.biz.config.IntegrationConfigService;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import javax.mail.internet.MimeMessage;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 邮箱验证码：下发、限频、校验。
 *
 * <p><b>为什么要它（v1.7.4 第 1 条）</b>：注册原本只要「邮箱格式对 + 密码合规」就能开号，
 * 也就是说任何人可以拿别人的邮箱、或者一个根本不存在的邮箱开一堆号。验证码把
 * 「这个地址确实能收信」变成注册的前提，登录时再验一次，等于把「邮箱是本人的」
 * 这件事在每次登录时都重新确认一遍。
 *
 * <p>验证码只放内存（{@link ConcurrentMap}），不落库也不落文件：
 * <ul>
 *   <li>有效期只有 {@code code-ttl-minutes} 分钟，进程重启后重新获取一次即可，代价极小；</li>
 *   <li>不落盘就不存在「历史验证码被翻出来重放」的问题。</li>
 * </ul>
 *
 * <p>三条防滥用规则（都在这里执行，调用方不必重复判断）：
 * <ol>
 *   <li><b>冷却</b> —— 同一邮箱 {@code resend-seconds} 秒内只能发一条，页面倒计时读同一个值；</li>
 *   <li><b>日限</b> —— 同一邮箱 24 小时内最多 {@code daily-limit} 条，防止拿别人的邮箱刷信；</li>
 *   <li><b>试错上限</b> —— 同一条码错 {@code max-attempts} 次即作废，必须重新获取，
 *       6 位数字（百万种）在这样的小窗口里无法爆破。</li>
 * </ol>
 *
 * <p>「未配置」是显式状态：{@code app.mail.username} / {@code password} 任一为空就不发信，
 * 直接报「邮件服务未配置」。绝不出现「提示已发送、其实什么都没发」的静默失败。
 */
@Service
public class EmailCodeService {

    private static final Logger log = LoggerFactory.getLogger(EmailCodeService.class);

    /** 注册场景 */
    public static final String SCENE_REGISTER = "register";
    /** 登录场景 */
    public static final String SCENE_LOGIN = "login";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MailProperties props;
    private final JavaMailSenderImpl sender;
    /**
     * 发信账号优先取「系统配置 → 第三方对接」里的配置行，行里没填才回落到 app.env。
     * 这样换发信邮箱不用登服务器改文件，改完下一次发码就生效。
     */
    private final IntegrationConfigService integrations;

    /** {@code 场景|邮箱 → 待校验的验证码} */
    private final ConcurrentMap<String, Code> codes = new ConcurrentHashMap<>();
    /** {@code 邮箱 → 发送窗口（冷却 + 当日条数）} */
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public EmailCodeService(MailProperties props, JavaMailSenderImpl sender, IntegrationConfigService integrations) {
        this.props = props;
        this.sender = sender;
        this.integrations = integrations;
    }

    /** 当前生效的发信账号（配置行 → app.env 兜底） */
    private String fromAccount() {
        String v = integrations == null ? "" : integrations.mailUsername();
        return v == null ? "" : v.trim();
    }

    /** 当前生效的发信授权码 */
    private String fromSecret() {
        String v = integrations == null ? "" : integrations.mailPassword();
        return v == null ? "" : v;
    }

    /** 邮件服务是否可用：开关打开 + 发信账号与口令都配了 */
    public boolean configured() {
        return props.isEnabled() && notBlank(fromAccount()) && notBlank(fromSecret());
    }

    /**
     * 登录是否要求验证码。
     *
     * <p>「邮件服务没配好」时不强制 —— 与注册不同，登录是存量用户的入口：
     * 配不上发信却在登录口强收验证码，等于把所有邮箱账号锁在门外。
     * 这时登录退回原来的「邮箱 + 密码」，日志里有明确告警；注册则直接停掉（见 {@link #requireOnRegister()}）。
     */
    public boolean requireOnLogin() {
        return props.isEnabled() && props.isRequireOnLogin() && configured();
    }

    /**
     * 注册是否要求验证码。
     *
     * <p>只看开关、不看「配没配」：注册是<b>新增入口</b>，没有发信能力就不该放人进来 ——
     * 放进来就退化成「随便填个邮箱就能开号」，正是这次要堵的口子。
     * 真去注册时会先被 {@link #send(String, String)} 用「邮件服务未配置」拦下。
     */
    public boolean requireOnRegister() {
        return props.isEnabled() && props.isRequireOnRegister();
    }

    /** 冷却秒数：页面倒计时用它，避免前后端各写一个 60 */
    public int resendSeconds() {
        return Math.max(1, props.getResendSeconds());
    }

    /** 验证码有效期（分钟） */
    public int ttlMinutes() {
        return Math.max(1, props.getCodeTtlMinutes());
    }

    /** 下发给前端的规则，与后端校验用的是同一份参数 */
    public Map<String, Object> rules() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configured", configured());
        m.put("requiredOnLogin", requireOnLogin());
        m.put("requiredOnRegister", requireOnRegister());
        m.put("resendSeconds", resendSeconds());
        m.put("ttlMinutes", ttlMinutes());
        m.put("codeLength", 6);
        return m;
    }

    /**
     * 发一条验证码。
     *
     * @param email 目标邮箱（已由调用方做过格式与归属校验）
     * @param scene {@link #SCENE_REGISTER} 或 {@link #SCENE_LOGIN}
     * @return 给页面的提示（含有效期）
     * @throws IllegalArgumentException 未配置 / 冷却中 / 超日限 / 发送失败
     */
    public String send(String email, String scene) {
        String mail = normalize(email);
        if (!configured()) {
            throw new IllegalArgumentException("邮件服务未配置，暂时无法获取验证码，请联系管理员");
        }
        long now = System.currentTimeMillis();
        sweep(now);

        Window w = windows.computeIfAbsent(mail, k -> new Window());
        synchronized (w) {
            String today = LocalDate.now().toString();
            if (!today.equals(w.day)) {
                w.day = today;
                w.dayCount = 0;
            }
            long waitMs = w.lastSentAt + resendSeconds() * 1000L - now;
            if (w.lastSentAt > 0 && waitMs > 0) {
                throw new IllegalArgumentException("验证码已发送，"
                        + Math.max(1, (waitMs + 999) / 1000) + " 秒后可重新获取");
            }
            if (w.dayCount >= Math.max(1, props.getDailyLimit())) {
                throw new IllegalArgumentException("该邮箱今天获取验证码的次数已达上限，请明天再试");
            }
            String code = sixDigits();
            String text = body(code, scene);
            try {
                sendMail(mail, subject(scene), text);
            } catch (Exception e) {
                log.warn("[mail] 验证码发送失败 {}：{}", maskEmail(mail), e.getMessage());
                throw new IllegalArgumentException("验证码发送失败，请稍后重试");
            }
            codes.put(key(mail, scene), new Code(code, now + ttlMinutes() * 60_000L));
            w.lastSentAt = now;
            w.dayCount++;
            log.info("[mail] 已向 {} 发送{}验证码（{} 分钟内有效）",
                    maskEmail(mail), sceneLabel(scene), ttlMinutes());
        }
        return "验证码已发送至 " + maskEmail(mail) + "，" + ttlMinutes() + " 分钟内有效";
    }

    /**
     * 校验验证码；通过后立即作废（一次性）。
     *
     * @throws IllegalArgumentException 未填 / 已失效 / 试错超限 / 不正确
     */
    public void verify(String email, String scene, String code) {
        String mail = normalize(email);
        String input = code == null ? "" : code.trim();
        if (input.isEmpty()) {
            throw new IllegalArgumentException("请填写邮箱验证码");
        }
        String k = key(mail, scene);
        Code c = codes.get(k);
        if (c == null || c.expireAt < System.currentTimeMillis()) {
            codes.remove(k);
            throw new IllegalArgumentException("验证码已失效，请重新获取");
        }
        synchronized (c) {
            if (c.attempts >= Math.max(1, props.getMaxAttempts())) {
                codes.remove(k);
                throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
            }
            if (!c.value.equals(input)) {
                c.attempts++;
                if (c.attempts >= Math.max(1, props.getMaxAttempts())) {
                    codes.remove(k);
                    throw new IllegalArgumentException("验证码错误次数过多，请重新获取");
                }
                throw new IllegalArgumentException("验证码不正确");
            }
            // 一次性：校验通过就删掉，同一个码不能用第二次
            codes.remove(k);
        }
    }

    /** 邮箱打码：日志与页面提示里只留首字符 + 域名，别把完整地址到处刷 */
    public static String maskEmail(String mail) {
        String s = mail == null ? "" : mail.trim();
        int at = s.indexOf('@');
        if (at <= 1) {
            return "***" + (at >= 0 ? s.substring(at) : "");
        }
        return s.charAt(0) + "***" + s.substring(at);
    }

    /* ---------------- 内部实现 ---------------- */

    private void sendMail(String to, String subject, String html) throws Exception {
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
        // 凭据每次现取：页面上换了发信账号，下一次发码就用新的（JavaMailSenderImpl 是可变的）
        String account = fromAccount();
        sender.setUsername(account);
        sender.setPassword(fromSecret());
        helper.setFrom(account, props.getFromName());
        helper.setTo(to);
        helper.setSubject(subject);
        helper.setText(html, true);
        sender.send(msg);
    }

    /** 邮件正文：只有一个验证码 + 用途 + 有效期，不放任何链接（避免被当成钓鱼邮件） */
    private String body(String code, String scene) {
        String purpose = SCENE_REGISTER.equals(scene) ? "注册账号" : "登录账号";
        return "<div style=\"font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif;"
                + "max-width:460px;margin:0 auto;padding:28px 26px;border:1px solid #e6e8ee;border-radius:14px;"
                + "color:#1f2430;background:#ffffff\">"
                + "<div style=\"font-size:16px;font-weight:600;letter-spacing:.5px\">影流数据</div>"
                + "<div style=\"height:1px;background:#eef0f5;margin:16px 0 20px\"></div>"
                + "<div style=\"font-size:14px;line-height:22px\">你正在" + purpose + "，验证码是：</div>"
                + "<div style=\"margin:18px 0;font-size:30px;font-weight:700;letter-spacing:8px;"
                + "font-family:ui-monospace,SFMono-Regular,Menlo,monospace;color:#2b5cff\">" + code + "</div>"
                + "<div style=\"font-size:13px;line-height:21px;color:#5b6478\">"
                + "验证码 " + ttlMinutes() + " 分钟内有效，只能使用一次。<br>"
                + "如果不是你本人操作，忽略这封邮件即可，你的账号不会有任何变化。</div>"
                + "</div>";
    }

    private static String subject(String scene) {
        return "影流数据 · " + sceneLabel(scene) + "验证码";
    }

    private static String sceneLabel(String scene) {
        return SCENE_REGISTER.equals(scene) ? "注册" : "登录";
    }

    private static String sixDigits() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** 清掉过期条目：验证码一小时没动过、发送窗口跨天，都可以扔了，避免长期运行后 map 只增不减 */
    private void sweep(long now) {
        Iterator<Map.Entry<String, Code>> it = codes.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expireAt + 3_600_000L < now) {
                it.remove();
            }
        }
        String today = LocalDate.now().toString();
        Iterator<Map.Entry<String, Window>> wi = windows.entrySet().iterator();
        while (wi.hasNext()) {
            Window w = wi.next().getValue();
            if (!today.equals(w.day) && w.lastSentAt + 86_400_000L < now) {
                wi.remove();
            }
        }
    }

    private static String key(String email, String scene) {
        return (SCENE_REGISTER.equals(scene) ? SCENE_REGISTER : SCENE_LOGIN) + "|" + normalize(email);
    }

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.trim().isEmpty();
    }

    /** 一条待校验的验证码 */
    private static final class Code {
        final String value;
        final long expireAt;
        int attempts;

        Code(String value, long expireAt) {
            this.value = value;
            this.expireAt = expireAt;
        }
    }

    /** 一个邮箱的发送窗口：上次发送时刻 + 当日已发条数 */
    private static final class Window {
        long lastSentAt;
        String day = "";
        int dayCount;
    }
}
