package com.stock.kline.config;

import com.stock.kline.watch.DingTalkNotifier;
import com.stock.kline.watch.WatchProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 钉钉推送器的装配。
 *
 * <p>这个 bean 原来声明在 {@code WatchConfig} 里，而后者带 {@code watch.enabled} 条件，
 * 于是关掉盯盘时连推送器一起消失 —— 用户反馈等模块就没得用了。
 *
 * <p>推送器本身只是「一段 HTTP 发送 + 一个配置引用」，没有副作用，
 * 所以挪到这里常驻装配：盯盘开着它照旧工作，盯盘关掉反馈也还能推。
 */
@Configuration
public class NotifyConfig {

    @Bean
    public DingTalkNotifier dingTalkNotifier(WatchProperties props) {
        return new DingTalkNotifier(props);
    }
}
