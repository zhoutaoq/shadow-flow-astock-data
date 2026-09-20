package io.github.zhoutaoq.shadowflow.notify.api;

/**
 * 通道 SPI：把一个通道的投递动作实现出来。
 *
 * <p>新增一种通道（企业微信、飞书、邮件、短信……）只需实现本接口并注册成 Bean，
 * {@link NotifyService} 会按 {@link #channel()} 自动路由，<b>不改任何既有代码</b>。
 *
 * <p>实现约定：
 * <ul>
 *   <li><b>必须线程安全</b>：同一实例会被多个业务线程并发调用；</li>
 *   <li><b>不得向外抛异常</b>：任何失败都收敛成 {@link NotifyResult#fail(String)}；</li>
 *   <li><b>不校验业务语义</b>：不做「这条消息该不该发」的判断，那是闸门（{@link NotifyGate}）的职责；</li>
 *   <li><b>不含任何业务词</b>：通道实现里出现的字符串只能是协议层的东西
 *       （JSON 字段名、消息类型、Content-Type），业务文案一律来自 {@link NotifyMessage}。</li>
 * </ul>
 */
public interface Notifier {

    /** 本实现负责的通道标识，小写，如 {@code dingtalk} */
    String channel();

    /**
     * 投递。
     *
     * @param endpoint 已经解析好的目标地址（不是逻辑键）
     * @param message  待投递的消息
     * @return 投递结果；实现不得返回 null，也不得抛异常
     */
    NotifyResult send(String endpoint, NotifyMessage message);
}
