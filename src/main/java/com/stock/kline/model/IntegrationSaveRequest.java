package com.stock.kline.model;

import java.util.List;
import java.util.Map;

/**
 * 保存第三方对接配置的请求体（v1.5.9）。
 *
 * <p>只提交「这次要动的那几项」，不是整份覆盖 —— 令牌与 webhook 在页面上是打码回显的，
 * 用户没碰的字段前端根本不会带上来，也就不该被当成「要清空」。
 */
public class IntegrationSaveRequest {

    /**
     * 字段名 → 新值，例如 <code>{"waizaoToken": "305c..."}</code>。
     *
     * <p>值为空串或整个字段缺席都表示「这一项保持原样」。
     */
    private Map<String, String> values;

    /**
     * 要清掉的字段名清单，例如 <code>["feedbackWebhook"]</code>。
     *
     * <p>清掉之后该项回落到 application.yml 的出厂值（而不是变成空值）。
     */
    private List<String> clear;

    public Map<String, String> getValues() {
        return values;
    }

    public void setValues(Map<String, String> values) {
        this.values = values;
    }

    public List<String> getClear() {
        return clear;
    }

    public void setClear(List<String> clear) {
        this.clear = clear;
    }
}
