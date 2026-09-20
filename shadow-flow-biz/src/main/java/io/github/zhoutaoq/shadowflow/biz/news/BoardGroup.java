package io.github.zhoutaoq.shadowflow.biz.news;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个平台的一次取数结果：页面上就是「一张卡片」。
 *
 * <p>把「平台标识 / 展示名 / 数据 / 失败原因」捆在一起返回，前端不用再自己拼状态：
 * <ul>
 *   <li>{@code error} 为 null 且 {@code rows} 为空 —— 平台本身不提供这块数据（如同花顺没有板块接口），
 *       卡片显示「该平台暂不提供」；</li>
 *   <li>{@code error} 非 null —— 这次取数失败，卡片显示原因并保留上一次的旧数据（由前端决定）。</li>
 * </ul>
 */
public class BoardGroup<T> {

    private String platform;
    private String label;
    private List<T> rows = new ArrayList<>();
    /** 取数失败的原因；成功时为 null */
    private String error;

    public BoardGroup() {
    }

    public BoardGroup(String platform, String label) {
        this.platform = platform;
        this.label = label;
    }

    public static <T> BoardGroup<T> ok(String platform, String label, List<T> rows) {
        BoardGroup<T> g = new BoardGroup<>(platform, label);
        g.setRows(rows == null ? new ArrayList<T>() : rows);
        return g;
    }

    public static <T> BoardGroup<T> fail(String platform, String label, String error) {
        BoardGroup<T> g = new BoardGroup<>(platform, label);
        g.setError(error);
        return g;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String platform) {
        this.platform = platform;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public List<T> getRows() {
        return rows;
    }

    public void setRows(List<T> rows) {
        this.rows = rows;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }
}
