package io.github.zhoutaoq.shadowflow.biz.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 买入 / 卖出策略组：多个判断组件按「或 / 且」组合成一个完整信号条件。
 *
 * <p>空条件组视为「永不触发」。
 */
public class BacktestRule {

    /** 任一组件满足即触发 */
    public static final String LOGIC_OR = "or";
    /** 全部组件都满足才触发 */
    public static final String LOGIC_AND = "and";

    private String logic = LOGIC_AND;
    private List<BacktestCondition> conditions = new ArrayList<>();

    public boolean isEmpty() {
        return conditions == null || conditions.isEmpty();
    }

    /** 校验策略组，返回错误信息；合法返回 null */
    public String validate() {
        if (!LOGIC_OR.equals(logic) && !LOGIC_AND.equals(logic)) {
            return "未知的组合关系: " + logic;
        }
        if (isEmpty()) {
            return "策略组至少需要一个判断组件";
        }
        for (BacktestCondition c : conditions) {
            if (c == null) {
                return "策略组中存在空的判断组件";
            }
            String err = c.validate();
            if (err != null) {
                return err;
            }
        }
        return null;
    }

    public String getLogic() {
        return logic;
    }

    public void setLogic(String logic) {
        this.logic = logic;
    }

    public List<BacktestCondition> getConditions() {
        return conditions;
    }

    public void setConditions(List<BacktestCondition> conditions) {
        this.conditions = conditions == null ? new ArrayList<>() : conditions;
    }
}
