package com.stock.kline.model;

/**
 * 「实时通知配置」保存请求。
 *
 * <p>单独一个请求体而不用 {@link SysConfig} 的原因：归属要不要落到系统级那一份，
 * 取决于<b>调用方有没有显式指定</b>，而 {@code SysConfig.ownerId} 是基本类型、
 * 缺省即为 0（系统级）。直接用实体接请求时，「没传」和「传了 0」长得一模一样，
 * 超管保存自己的令牌会被误写进系统级 —— 所以这里用可空的 {@link Long} 把「未指定」
 * 表达出来，由服务端裁决最终归属。
 */
public class SysConfigSaveRequest {

    /** 归属用户 id：不传（null）= 我自己的那一份；0 = 系统级那一份（仅超管有效） */
    private Long ownerId;
    private String configScene;
    private String configKey;
    private String configValue;
    private String remark;

    /** 是否显式指定了「写系统级」 */
    public boolean systemScoped() {
        return ownerId != null && ownerId == SysConfig.SYSTEM_OWNER;
    }

    /** 转成落库实体；最终归属由调用方再按登录态裁决一次 */
    public SysConfig toConfig() {
        SysConfig cfg = new SysConfig();
        cfg.setConfigScene(configScene);
        cfg.setConfigKey(configKey);
        cfg.setConfigValue(configValue);
        cfg.setRemark(remark);
        return cfg;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getConfigScene() {
        return configScene;
    }

    public void setConfigScene(String configScene) {
        this.configScene = configScene;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }
}
