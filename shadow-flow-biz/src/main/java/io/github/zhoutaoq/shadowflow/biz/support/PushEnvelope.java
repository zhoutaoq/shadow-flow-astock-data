package io.github.zhoutaoq.shadowflow.biz.support;

import io.github.zhoutaoq.shadowflow.notify.support.NotifyText;

/**
 * 外发消息的信封（前缀）—— <b>业务侧的品牌与主机标识都在这里，不进通知 SDK</b>。
 *
 * <p>前缀形态：
 * <pre>
 * [关键词-主机] 2026-09-20 13:20:00
 * </pre>
 * 两样东西都是给人看的：
 * <ul>
 *   <li><b>关键词</b>满足群机器人「自定义关键词」校验（服务端按子串匹配，
 *       <code>[关键词-1.2.3.4]</code> 一样能命中）；关键词由调用方按场景给，
 *       为空则整段标签去掉。</li>
 *   <li><b>主机</b>取出口公网 IP，用来区分是哪台机器发的；探测不到时退成
 *       <code>[关键词]</code>，不影响送达。</li>
 *   <li>标签后是<b>发送时刻</b>：群里翻历史消息、事后复盘对时间，
 *       不必再去服务器上翻日志。取的是「发送这一刻」而不是业务数据时间。</li>
 * </ul>
 *
 * <p>为什么放在业务侧而不是 SDK：关键词是<b>品牌词</b>、公网 IP 是部署环境的事实，
 * 两者都与「怎么把一段文本投递出去」无关。SDK 只提供一个逐字前缀参数，
 * 内容由业务决定。
 */
public final class PushEnvelope {

    private PushEnvelope() {
    }

    /**
     * 生成标准前缀。
     *
     * @param keyword 关键词（品牌词）；为空则不加标签，只保留时间戳
     */
    public static String of(String keyword) {
        String tag = NotifyText.keywordTag(keyword, PublicIp.publicIp());
        String stamp = NotifyText.now();
        return tag.isEmpty() ? stamp : tag + " " + stamp;
    }

    /** 生成前缀，但用指定的主机标识（不探测公网 IP，便于测试与内网部署） */
    public static String of(String keyword, String host) {
        String tag = NotifyText.keywordTag(keyword, host);
        String stamp = NotifyText.now();
        return tag.isEmpty() ? stamp : tag + " " + stamp;
    }

    /** 启动时后台预热一次公网 IP，让第一条消息不必同步等网络 */
    public static void warmUp() {
        PublicIp.warmUp();
    }
}
