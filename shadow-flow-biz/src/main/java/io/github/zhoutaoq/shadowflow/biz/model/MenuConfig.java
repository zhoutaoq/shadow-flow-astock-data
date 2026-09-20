package io.github.zhoutaoq.shadowflow.biz.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 全站菜单的当前生效配置（接口 {@code /api/menu} 的响应体）。
 *
 * <p><b>为什么菜单要能配</b>：菜单是给使用者看的第一层门面，
 * 名称与顺序会随运营口径变（「用户反馈」改名「问题反馈」、把常用的往前提），
 * 而每次改都得动前端代码 + 发一次版，代价与收益完全不成比例。
 * 所以把「名称」与「顺序」抽出来做成系统级配置，超管在「系统配置」页点一下即生效。
 *
 * <p><b>能配的只有名称与顺序，别的一概不给配</b>：
 * <ul>
 *   <li><b>地址（href）</b>不能配 —— 它是代码里的路由，配错了就是一个 404 的死菜单；</li>
 *   <li><b>可见性（登录 / 超管）</b>不能配 —— 那是权限，不是展示偏好；
 *       把「用户管理」的 admin 标记做成可配，等于开了一个越权入口。</li>
 * </ul>
 * 所以前端只拿 {@link #items} 里回带的 {@code key} 去索引内置那份
 * {@code NAV_ITEMS}（地址与权限都在里面），配置只覆盖名称与顺序；
 * 配置里出现内置没有的 key 一律忽略，漏配的项按内置顺序补回。
 *
 * <p>{@link #customized} 为 false 表示还没配置过（配置行不存在），
 * 这时 {@link #items} 是内置默认值 —— 页面照常渲染，不必先有配置。
 */
public class MenuConfig {

    /** 是否已被超管配置过（false = 仍在使用内置默认的名称与顺序） */
    private boolean customized;
    /** 按配置后的顺序排列的菜单项 */
    private List<Item> items = new ArrayList<>();

    public boolean isCustomized() {
        return customized;
    }

    public void setCustomized(boolean customized) {
        this.customized = customized;
    }

    public List<Item> getItems() {
        return items;
    }

    public void setItems(List<Item> items) {
        this.items = items;
    }

    /** 一个菜单项的展示信息 */
    public static class Item {

        /** 稳定标识（与前端 NAV_ITEMS 的 key 一致），页面靠它找地址与权限 */
        private String key = "";
        /** 展示名称 */
        private String text = "";
        /** 地址：只读回显，供配置页展示「这一项是哪个页面」，不参与保存 */
        private String href = "";

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

        public String getHref() {
            return href;
        }

        public void setHref(String href) {
            this.href = href;
        }
    }
}
