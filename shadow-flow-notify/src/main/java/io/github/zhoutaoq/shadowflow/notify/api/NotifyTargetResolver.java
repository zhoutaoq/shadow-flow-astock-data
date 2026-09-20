package io.github.zhoutaoq.shadowflow.notify.api;

/**
 * 目标解析 SPI：把「逻辑目标键」翻译成「真实投递地址」。
 *
 * <p>为什么要多这一层：通知的目标地址往往是<b>运行期可变且落在别处</b>的
 * —— 存在配置表里、由管理员在页面上改、或者按接收人不同而不同。
 * SDK 不该知道这些，只认「给我一个键，还我一个地址」。
 *
 * <p>实现约定：<b>认不出来的键必须原样返回</b>。这样调用方也可以直接把地址当键传进来，
 * 不必先判断「这个键是逻辑键还是地址」，少一层分支就少一处出错的地方。
 *
 * <pre>
 * 例：resolve("dingtalk", "system")        -&gt; "https://.../robot/send?access_token=..."
 *      resolve("dingtalk", "https://x/y")   -&gt; "https://x/y"          （原样返回）
 * </pre>
 */
@FunctionalInterface
public interface NotifyTargetResolver {

    /**
     * 解析目标地址。
     *
     * @param channel 通道标识
     * @param target  逻辑目标键，也可能是地址本身
     * @return 真实投递地址；无法解析时返回空串表示「没有可用目标」
     */
    String resolve(String channel, String target);

    /** 默认解析器：原样返回，即「target 就是地址」 */
    static NotifyTargetResolver identity() {
        return (channel, target) -> target == null ? "" : target;
    }
}
