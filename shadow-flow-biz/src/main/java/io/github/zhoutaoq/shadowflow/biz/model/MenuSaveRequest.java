package io.github.zhoutaoq.shadowflow.biz.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 「系统配置」页保存菜单配置的请求体。
 *
 * <p>只提交<b>顺序 + 名称</b>两个维度：{@code items} 按用户拖动后的顺序排列，
 * 每项只带 key 与新名称。地址与权限不在这里 —— 它们属于代码里的路由与守卫，
 * 放开给页面改等于让人把菜单改成一排 404，或者把超管入口暴露出去。
 */
public class MenuSaveRequest {

    /** 按目标顺序排列的菜单项；缺项、空名称都表示「这一项保持内置默认」 */
    private List<Item> items = new ArrayList<>();

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    /** 单项：key 定位、text 是新名称 */
    public static class Item {

        private String key;
        private String text;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
