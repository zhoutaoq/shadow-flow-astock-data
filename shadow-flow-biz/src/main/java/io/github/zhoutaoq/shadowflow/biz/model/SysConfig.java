package io.github.zhoutaoq.shadowflow.biz.model;

/**
 * 通用配置项（waizao_sys_config）。
 *
 * <p>这张表按「归属用户 + 场景 + 键」存运行期可变的变量，不绑定任何供应商：
 * 目前只有 waizao 的 token 会频繁更换，所以存在这里；接口地址、超时、
 * 并发度这些稳定的参数一律走 application.yml。
 *
 * <p>v1.5 起配置按用户隔离：{@link #ownerId} 是用户名下的行，
 * {@link #SYSTEM_OWNER}（0）表示系统级 —— 用户没配过时用它兜底。
 */
public class SysConfig {

    /** 系统级归属：未登录、或该用户没单独配过时的兜底行 */
    public static final long SYSTEM_OWNER = 0L;

    private Long id;
    /** 归属用户 id；0 表示系统级兜底（未登录 / 该用户没单独配过） */
    private long ownerId = SYSTEM_OWNER;
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

    public long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(long ownerId) {
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
