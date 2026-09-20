package io.github.zhoutaoq.shadowflow.notify.api;

/**
 * 发送闸门 SPI：决定「这一刻这条消息该不该发出去」。
 *
 * <p>做成 SPI 而不是写死在 SDK 里，是因为「什么时候允许打扰别人」这件事
 * <b>掌握在业务侧</b>：SDK 可以提供一个通用的「静默时段」实现
 * （见 {@code policy.AllowedWindowsGate}），但业务往往还要叠自己的规则。
 *
 * <p>实现约定：
 * <ul>
 *   <li>放行返回 {@code null}；拦截返回<b>原因文本</b>（只进日志，不面向用户）；</li>
 *   <li>必须线程安全、不得抛异常、不得有任何阻塞式 IO；</li>
 *   <li>多个闸门之间是「与」关系：任一拦截即不发送。</li>
 * </ul>
 */
@FunctionalInterface
public interface NotifyGate {

    /** 拦截原因；放行返回 null */
    String blockReason(NotifyMessage message);
}
