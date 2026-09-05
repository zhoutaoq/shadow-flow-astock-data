package com.stock.kline.model;

/**
 * 通用配置项（waizao_sys_config）。
 *
 * <p>这张表按「场景 + 键」存运行期可变的变量，不绑定任何供应商：
 * 目前只有 waizao 的 token 会频繁更换，所以存在这里；接口地址、超时、
 * 并发度这些稳定的参数一律走 application.yml。
 */
public class SysConfig {

    private Long id;
    private String configScene = "";
    private String configKey = "";
    private String configValue = "";
    private String remark;
    private String createTime;
    private String updateTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
