package com.stock.kline.account;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 账户模块自己的定时能力（需求 v1.5.6 第 ⑥ 条：每天 00:00 的可用数量日切）。
 *
 * <p>为什么单开一个而不是蹭盯盘那边已有的 {@code @EnableScheduling}：
 * {@code WatchConfig} 整体挂在 {@code watch.enabled} 开关下，关掉盯盘时
 * 账户的日切也会跟着一起失效 —— 这两件事没有任何依赖关系，不该绑在一起。
 * 两边同时开着也无害：{@code @EnableScheduling} 注册的是同一个基础设施 bean
 * （bean 名固定为 {@code internalScheduledAnnotationProcessor}），重复注册会被忽略。
 */
@Configuration
@EnableScheduling
public class AccountScheduleConfig {
}
