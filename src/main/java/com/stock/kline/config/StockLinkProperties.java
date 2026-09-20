package com.stock.kline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票名称跳转目标配置（application.yml 的 {@code app.stock-link} 段）。
 *
 * <p>需求 v1.5.8 第 1 条：全站股票名称加超链接，点击开新窗口跳转。
 * 「跳到哪」有两层，各管一半，互不越界：
 * <ul>
 *   <li><b>URL 模板</b>（本类，application.yml）—— 属于稳定的寻址规则，
 *       平台换域名、改路径只改配置，不改代码；</li>
 *   <li><b>启用哪些 + 默认跳哪个</b>（{@code waizao_sys_config} 场景 {@code stock-link} 的系统级行）——
 *       属于运行期会变的开关，超管在「系统配置」页点一下就生效，不用重启。</li>
 * </ul>
 *
 * <p>模板里的占位符（前端按目标逐个替换，见 common.js 的 {@code stockUrl}）：
 * <ul>
 *   <li>{@code {code}} —— 6 位代码原文，如 {@code 600410}；</li>
 *   <li>{@code {market}} —— 交易所前缀 {@code SH} / {@code SZ}；</li>
 *   <li>{@code {name}} —— 股票名称，<b>占位符自带 URL 编码</b>，直接替换即可。</li>
 * </ul>
 */
@Component
@ConfigurationProperties(prefix = "app.stock-link")
public class StockLinkProperties {

    /** 总开关：false 时前端一律按纯文本渲染股票名称，一个链接都不生成 */
    private boolean enabled = true;

    /** 默认跳转目标（点名称时开哪个）；目标被停用时会自动落到第一个启用的 */
    private String defaultTarget = "chart";

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

    public List<Target> getTargets() {
        return targets;
    }

    public void setTargets(List<Target> targets) {
        this.targets = targets;
    }

    /** 一个跳转目标：一个平台 = 一条模板 */
    public static class Target {

        /** 稳定标识，落配置与前端识别都用它（不要改成会变的展示名） */
        private String key = "";
        /** 完整名称，前端 title / 系统配置页展示 */
        private String label = "";
        /** 简称，股票名称旁边那排小图标上用（2 个字以内最合适） */
        private String shortLabel = "";
        /** URL 模板，占位符见类注释 */
        private String urlTemplate = "";
        /** 是否站外平台：站外的加 rel="noopener noreferrer" 与 ↗ 角标 */
        private boolean external;

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

        public String getUrlTemplate() {
            return urlTemplate;
        }

        public void setUrlTemplate(String urlTemplate) {
            this.urlTemplate = urlTemplate;
        }

        public boolean isExternal() {
            return external;
        }

        public void setExternal(boolean external) {
            this.external = external;
        }
    }
}
