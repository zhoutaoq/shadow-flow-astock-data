package io.github.zhoutaoq.shadowflow.notify.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.ZoneId;

/**
 * 通知 SDK 的配置，绑定前缀 <code>shadowflow.notify</code>。
 *
 * <p>这里只放「任何使用本 SDK 的项目都需要的通用开关」：
 * 总开关、允许发送时段、超时。
 *
 * <p><b>刻意不放进来的</b>：目标地址（由 {@code NotifyTargetResolver} 从调用方的
 * 配置源解析）、业务闸门（由调用方实现 {@code NotifyGate} 注入）、
 * 消息文案（SDK 不生成任何业务文本）。
 */
@ConfigurationProperties(prefix = "shadowflow.notify")
public class NotifyProperties {

    /**
     * <b>通知总开关</b>：false 时一条都发不出去，只在应用日志里留痕。
     *
     * <p>默认 true —— 本 SDK 的定位是「装配好就能用」，
     * 「本地开发不想往群里刷消息」属于调用方的环境策略，
     * 调用方在自己的启动参数里关掉即可。
     */
    private boolean enabled = true;

    /** 默认通道标识；消息里没写 channel 时用它 */
    private String defaultChannel = "dingtalk";

    /**
     * 允许发送的时段，形如 {@code "09:15-11:30,13:00-15:00"}（逗号分隔，闭区间）。
     *
     * <p>空白 = 不限制时段。
     */
    private String allowedWindows = "";

    /** 是否启用上面的时段闸门；false 时时段配置被忽略（适合「任何时刻都能收到」的场景） */
    private boolean enforceAllowedWindows = false;

    /** 允许发送的星期，如 {@code "MO,TU,WE,TH,FR"}；空白 = 不限 */
    private String daysOfWeek = "MO,TU,WE,TH,FR";

    /**
     * 时段判断所用时区。
     *
     * <p><b>强烈建议显式配置</b>：留空取服务器默认时区，
     * 而服务器时区与业务时区不一致时，时段判断会整体偏移，
     * 表现为「明明在窗口内却收不到消息」。
     */
    private String zone = "";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 5000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 10000;

    /** 解析时区；配了非法值就退回系统默认并保持可用 */
    public ZoneId zone() {
        if (zone == null || zone.trim().isEmpty()) {
            return ZoneId.systemDefault();
        }
        try {
            return ZoneId.of(zone.trim());
        } catch (Exception e) {
            return ZoneId.systemDefault();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultChannel() {
        return defaultChannel;
    }

    public void setDefaultChannel(String defaultChannel) {
        this.defaultChannel = defaultChannel == null || defaultChannel.trim().isEmpty()
                ? "dingtalk" : defaultChannel.trim().toLowerCase();
    }

    public String getAllowedWindows() {
        return allowedWindows;
    }

    public void setAllowedWindows(String allowedWindows) {
        this.allowedWindows = allowedWindows == null ? "" : allowedWindows.trim();
    }

    public boolean isEnforceAllowedWindows() {
        return enforceAllowedWindows;
    }

    public void setEnforceAllowedWindows(boolean enforceAllowedWindows) {
        this.enforceAllowedWindows = enforceAllowedWindows;
    }

    public String getDaysOfWeek() {
        return daysOfWeek;
    }

    public void setDaysOfWeek(String daysOfWeek) {
        this.daysOfWeek = daysOfWeek == null ? "" : daysOfWeek.trim();
    }

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone == null ? "" : zone.trim();
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }
}
