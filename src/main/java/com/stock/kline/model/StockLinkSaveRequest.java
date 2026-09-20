package com.stock.kline.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 「系统配置」页保存股票名称跳转的请求体。
 *
 * <p>只提交<b>开关</b>（总开关 / 每个目标是否启用 / 默认跳哪个），
 * URL 模板不在这里 —— 它属于 application.yml 的稳定配置，
 * 页面上不开放编辑，免得把平台地址改坏之后全站链接一起失效。
 */
public class StockLinkSaveRequest {

    /** 总开关 */
    private Boolean enabled;
    /** 默认跳转目标 key（必须是本次提交里启用的目标之一） */
    private String defaultTarget;
    /** 各目标的启用状态 */
    private List<TargetState> targets = new ArrayList<>();

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultTarget() {
        return defaultTarget;
    }

    public void setDefaultTarget(String defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    public List<TargetState> getTargets() {
        return targets;
    }

    public void setTargets(List<TargetState> targets) {
        this.targets = targets;
    }

    /** 单个目标的启用状态 */
    public static class TargetState {

        private String key;
        private Boolean enabled;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }
    }
}
