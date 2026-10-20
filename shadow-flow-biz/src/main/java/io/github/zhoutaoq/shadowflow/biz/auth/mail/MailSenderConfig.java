package io.github.zhoutaoq.shadowflow.biz.auth.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

/**
 * 发信客户端。
 *
 * <p>不启用 Spring Boot 的 {@code spring.mail} 自动装配：那套东西只要 classpath 上有
 * starter-mail 就会按 {@code spring.mail.*} 建一个 sender，而本项目的发信参数统一在
 * {@code app.mail.*}（与 app.env 的 MAIL_USERNAME / MAIL_PASSWORD 对应），
 * 两套前缀并存只会让人搞不清「到底改哪个」。这里显式建一个，参数只认一处。
 *
 * <p>超时都收得比较短（连接 10s / 读 15s）：注册与登录是同步等验证码的路径，
 * SMTP 卡住时不能让用户对着转圈等半分钟 —— 发不出去就报错，让他重试。
 */
@Configuration
public class MailSenderConfig {

    private static final Logger log = LoggerFactory.getLogger(MailSenderConfig.class);

    @Bean
    public JavaMailSenderImpl javaMailSender(MailProperties props) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(props.getHost());
        sender.setPort(props.getPort());
        sender.setDefaultEncoding("UTF-8");
        if (props.getUsername() != null && !props.getUsername().trim().isEmpty()) {
            sender.setUsername(props.getUsername().trim());
            sender.setPassword(props.getPassword());
        }
        Properties p = sender.getJavaMailProperties();
        p.put("mail.transport.protocol", "smtp");
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.ssl.enable", String.valueOf(props.isSsl()));
        p.put("mail.smtp.starttls.enable", String.valueOf(!props.isSsl()));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "15000");
        p.put("mail.smtp.writetimeout", "15000");
        log.info("[mail] SMTP 客户端已装配：{}:{}（ssl={}，出厂账号 {}；系统配置页可覆盖）",
                props.getHost(), props.getPort(), props.isSsl(),
                props.getUsername() == null || props.getUsername().trim().isEmpty()
                        ? "未配置" : EmailCodeService.maskEmail(props.getUsername()));
        return sender;
    }
}
