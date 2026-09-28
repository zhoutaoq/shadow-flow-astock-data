package io.github.zhoutaoq.shadowflow.biz.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票名称跳转的当前生效配置（接口 {@code /api/stock-link} 的响应体）。
 *
 * <p>页面拿到它之后自己拼最终 URL：模板是死的、股票是活的，
 * 服务端不可能替每个页面把每一行的链接都拼好。
 *
 * <p>三层兜底的关系：
 * <ol>
 *   <li>{@link #enabled} = false：全站不生成链接，名称按纯文本显示；</li>
 *   <li>某个 {@link Target#isEnabled()} = false：这个平台不出现（点名称也不会跳到它）；</li>
 *   <li>{@link #defaultTarget}：点名称直接开哪个。它一定是一个启用的目标 ——
 *       配置里写的是被停用的那个时，服务端已经换成第一个启用的，前端不用再判。</li>
 * </ol>
 */
public class StockLink {

    /** 总开关 */
    private boolean enabled = true;
    /** 点名称直接打开的目标 key */
    private String defaultTarget = "";
    /**
     * 本站的对外地址（{@code http://主机:端口}），空表示还没填。
     *
     * <p>只有站内目标（本系统 K 线）需要它：相对路径在钉钉、微信这类站外场合点不开，
     * 必须补成绝对地址。没填时站内目标在推送消息里不可用（会自动顺延到站外平台），
     * 浏览器里不受影响 —— 页面本来就有「当前站点」这个概念。
     */
    private String siteBaseUrl = "";
    private List<Target> targets = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDefaultTarget() {
        return defaultTarget;
    }

    public void setDefaultTarget(String defaultTarget) {
        this.defaultTarget = defaultTarget;
    }

    public String getSiteBaseUrl() {
        return siteBaseUrl;
    }

    public void setSiteBaseUrl(String siteBaseUrl) {
        this.siteBaseUrl = siteBaseUrl;
    }

    public List<Target> getTargets() {
        return targets;
    }

    public void setTargets(List<Target> targets) {
        this.targets = targets;
    }

    /** 一个可跳转的目标（= 一个平台） */
    public static class Target {

        private String key = "";
        private String label = "";
        private String shortLabel = "";
        /** URL 模板，占位符 {code} / {market} / {name}（{name} 自带 URL 编码） */
        private String url = "";
        /** 站外平台：前端加 ↗ 角标与 noopener */
        private boolean external;
        private boolean enabled = true;

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }

        public String getShortLabel() {
            return shortLabel;
        }

        public void setShortLabel(String shortLabel) {
            this.shortLabel = shortLabel;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public boolean isExternal() {
            return external;
        }

        public void setExternal(boolean external) {
            this.external = external;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
