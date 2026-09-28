package io.github.zhoutaoq.shadowflow.biz.service;

import io.github.zhoutaoq.shadowflow.biz.config.StockLinkProperties;
import io.github.zhoutaoq.shadowflow.biz.model.StockLink;
import io.github.zhoutaoq.shadowflow.biz.model.StockLinkSaveRequest;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import io.github.zhoutaoq.shadowflow.biz.repo.SysConfigRepository;
import io.github.zhoutaoq.shadowflow.biz.support.SiteBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 股票名称跳转的配置读写（需求 v1.5.8 第 1 条）。
 *
 * <p>URL 模板来自 application.yml（见 {@link StockLinkProperties}），
 * 开关状态落在系统级配置行（{@code ownerId = 0}，场景 {@code stock-link}）——
 * 与「首页背景行情」同一套路：站点上所有人共用一份，按用户隔离的只有令牌那种凭据。
 *
 * <p>存储形态刻意用<b>停用名单</b>而不是启用名单：以后往 yml 里加一个新平台，
 * 老配置（只写了停用了谁）不会把它误伤成「未启用」，新平台默认就是开着的。
 *
 * <p>三级兜底：默认目标被停用 → 顺延到第一个启用的目标；全部停用 → 总开关置 false。
 * 也就是说页面永远不会拿到「有链接、但点了不知道去哪」的状态。
 */
@Service
public class StockLinkService {

    private static final Logger log = LoggerFactory.getLogger(StockLinkService.class);

    /** 配置落点：系统级 + 场景 stock-link */
    private static final String SCENE = "stock-link";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DEFAULT = "defaultTarget";
    private static final String KEY_DISABLED = "disabledTargets";
    private static final String REMARK = "股票名称跳转目标";

    private final StockLinkProperties props;
    private final SysConfigRepository configs;
    private final SiteBase site;

    public StockLinkService(StockLinkProperties props, SysConfigRepository configs, SiteBase site) {
        this.props = props;
        this.configs = configs;
        this.site = site;
    }

    /** 站点对外地址的落点：与跳转开关同一层（系统级 + 场景 site） */
    private static final String SITE_SCENE = "site";
    private static final String SITE_KEY = "base-url";
    private static final String SITE_REMARK = "站点对外地址";

    /** 当前生效的一份（未配置过时就是 application.yml 里的默认） */
    public StockLink current() {
        boolean enabled = props.isEnabled();
        String saved = trim(configs.get(SCENE, KEY_ENABLED));
        if ("false".equalsIgnoreCase(saved)) {
            enabled = false;
        } else if ("true".equalsIgnoreCase(saved)) {
            enabled = true;
        }
        // 未配置过的项一律回落到 yml 的默认值，不会因为配置行里少写一个键就整段失效
        Set<String> disabled = splitKeys(configs.get(SCENE, KEY_DISABLED));
        String wantDefault = trim(configs.get(SCENE, KEY_DEFAULT));
        if (!StringUtils.hasText(wantDefault)) {
            wantDefault = trim(props.getDefaultTarget());
        }
        StockLink out = assemble(enabled, wantDefault, disabled);
        String raw = trim(configs.get(SITE_SCENE, SITE_KEY));
        out.setSiteBaseUrl(StringUtils.hasText(raw) ? raw : ymlSiteBase());
        return out;
    }

    /** 配置文件里的出厂值（页面没填过地址时展示它，省得超管以为压根没这项） */
    private String ymlSiteBase() {
        String v = trim(props.getSiteBaseUrl());
        return v.isEmpty() ? "" : v;
    }

    /**
     * 保存开关状态。
     *
     * <p>入参里的目标 key 必须是 application.yml 里真实存在的 —— 前端传了个
     * 拼错的 key 时直接报错，而不是悄悄写进一行永远不生效的配置。
     */
    public StockLink save(StockLinkSaveRequest req) {
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        List<StockLinkProperties.Target> all = props.getTargets();
        if (all == null || all.isEmpty()) {
            throw new IllegalArgumentException("系统没有配置任何跳转目标，请检查 application.yml 的 app.stock-link");
        }
        boolean enabled = req.getEnabled() == null || req.getEnabled();

        // 前端提交的是「启用名单」，这里换算成停用名单落库
        Set<String> submittedOn = new LinkedHashSet<>();
        if (req.getTargets() != null) {
            for (StockLinkSaveRequest.TargetState t : req.getTargets()) {
                if (t == null || !StringUtils.hasText(t.getKey())) {
                    continue;
                }
                if (t.getEnabled() == null || t.getEnabled()) {
                    submittedOn.add(t.getKey().trim());
                }
            }
        }
        Set<String> known = new LinkedHashSet<>();
        for (StockLinkProperties.Target t : all) {
            known.add(trim(t.getKey()));
        }
        for (String k : submittedOn) {
            if (!known.contains(k)) {
                throw new IllegalArgumentException("未知的跳转目标：" + k);
            }
        }
        Set<String> disabled = new LinkedHashSet<>(known);
        disabled.removeAll(submittedOn);

        String wantDefault = trim(req.getDefaultTarget());
        if (StringUtils.hasText(wantDefault) && !known.contains(wantDefault)) {
            throw new IllegalArgumentException("未知的默认跳转目标：" + wantDefault);
        }

        StockLink resolved = assemble(enabled, wantDefault, disabled);
        if (enabled && !resolved.isEnabled()) {
            throw new IllegalArgumentException("至少启用一个跳转目标");
        }

        String siteBase = req.getSiteBaseUrl() == null ? null : req.getSiteBaseUrl().trim();
        if (siteBase != null && !siteBase.isEmpty() && SiteBase.normalize(siteBase) == null) {
            throw new IllegalArgumentException("站点对外地址格式不对，应形如 http://1.2.3.4:8080");
        }
        saveRow(KEY_ENABLED, enabled ? "true" : "false");
        saveRow(KEY_DEFAULT, resolved.getDefaultTarget());
        saveRow(KEY_DISABLED, String.join(",", disabled));
        if (siteBase != null) {
            saveRow(SITE_SCENE, SITE_KEY, siteBase, SITE_REMARK);
            resolved.setSiteBaseUrl(siteBase);
        }
        log.info("[stock-link] 股票名称跳转已更新：{}，默认 {}，停用 [{}]，站点对外地址 [{}]",
                enabled ? "启用" : "停用", resolved.getDefaultTarget(), String.join(",", disabled),
                resolved.getSiteBaseUrl());
        return resolved;
    }

    /** 恢复默认：删掉这几行配置，回到 application.yml 的默认口径 */
    public void reset() {
        for (SysConfig row : configs.list(SysConfig.SYSTEM_OWNER)) {
            if ((SCENE.equals(row.getConfigScene()) || SITE_SCENE.equals(row.getConfigScene()))
                    && row.getId() != null) {
                configs.delete(SysConfig.SYSTEM_OWNER, row.getId());
            }
        }
        log.info("[stock-link] 股票名称跳转已恢复默认");
    }

    /**
     * 把「总开关 + 期望的默认目标 + 停用名单」装配成一份可直接下发的配置。
     *
     * <p>默认目标被停用时顺延到第一个启用的目标（不是报错）—— 超管在页面上
     * 边停用边改默认值，中间态没必要打断他；全部停用时总开关置 false，
     * 页面据此退回纯文本，不会出现「点了没反应」的链接。
     */
    private StockLink assemble(boolean enabled, String wantDefault, Set<String> disabled) {
        StockLink out = new StockLink();
        List<StockLink.Target> targets = new ArrayList<>();
        String fallback = "";
        for (StockLinkProperties.Target src : props.getTargets()) {
            if (src == null || !StringUtils.hasText(src.getKey())) {
                continue;
            }
            String key = trim(src.getKey());
            boolean on = !disabled.contains(key);
            StockLink.Target t = new StockLink.Target();
            t.setKey(key);
            t.setLabel(trim(src.getLabel()));
            t.setShortLabel(StringUtils.hasText(src.getShortLabel()) ? trim(src.getShortLabel()) : trim(src.getLabel()));
            t.setUrl(trim(src.getUrlTemplate()));
            t.setExternal(src.isExternal());
            t.setEnabled(on);
            targets.add(t);
            if (on && !StringUtils.hasText(fallback)) {
                fallback = key;
            }
        }
        String def = wantDefault;
        boolean defUsable = false;
        for (StockLink.Target t : targets) {
            if (StringUtils.hasText(def) && def.equals(t.getKey()) && t.isEnabled()) {
                defUsable = true;
                break;
            }
        }
        if (!defUsable) {
            def = fallback;
        }
        out.setTargets(targets);
        // 一个能用的目标都没有时，总开关连同失效：页面就不会渲染出点了没反应的链接
        out.setEnabled(enabled && StringUtils.hasText(def));
        out.setDefaultTarget(StringUtils.hasText(def) ? def : "");
        return out;
    }

    /**
     * 交易所前缀推导（雪球这类「市场前缀 + 代码」的平台要用）。
     *
     * <p>口径与前端 {@code common.js stockMarketOf} 完全一致，<b>两边必须共改</b>：
     * 6 / 9 开头是沪市（含 688 科创板），4 / 8 开头是北交所，其余按深市算。
     * 指数代码（如 000001 上证指数）在这里会被判成深市 —— 同一个 6 位代码
     * 在两个交易所都成立时无法从代码本身区分，这是固有歧义，不为了它去猜。
     */
    public static String marketOf(String code) {
        String c = code == null ? "" : code.trim();
        char head = c.isEmpty() ? '0' : c.charAt(0);
        if (head == '6' || head == '9') {
            return "SH";
        }
        if (head == '4' || head == '8') {
            return "BJ";
        }
        return "SZ";
    }

    /**
     * 整卡跳转用的<b>唯一地址</b>：与 PC 页面点标的跳的是同一个源 ——
     * 系统配置页选的默认目标（{@code defaultTarget}）就是这里的结果。
     *
     * <p>口径说明（只给一个地址，不列候选）：
     * 页面上一次点击只会去一个地方，就是默认目标；推送消息同理，
     * 不该在一条消息里塞好几个候选让用户挑。所以这里<b>只返回一个地址</b>。
     *
     * <p>三级兜底，任何一级拿不到就顺延，全拿不到返回 null（调用方据此退回不带跳转的卡片，
     * 消息照发）：
     * <ol>
     *   <li>默认目标（可能是站内页面，此时补成绝对地址）；</li>
     *   <li>第一个能用的启用目标；</li>
     *   <li>没有可用目标 → null。</li>
     * </ol>
     *
     * <p>拼装出异常一律按「没有地址」处理 —— 链接问题绝不能阻塞告警本身。
     */
    public String primaryLink(String code, String name) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        try {
            StockLink link = current();
            if (link == null || !link.isEnabled() || link.getTargets() == null) {
                return null;
            }
            String c = code.trim();
            String n = name == null || name.trim().isEmpty() ? c : name.trim();
            String url = urlOf(link, link.getDefaultTarget(), c, n);
            if (url != null) {
                return url;
            }
            // 默认目标不可用（被停用、或站内地址补不出绝对地址）时顺延到第一个能用的
            for (StockLink.Target t : link.getTargets()) {
                if (t == null || !t.isEnabled()) {
                    continue;
                }
                url = urlOf(link, t.getKey(), c, n);
                if (url != null) {
                    return url;
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("[stock-link] 跳转地址拼装失败 {}({})，本条消息不跳转：{}", name, code, e.getMessage());
            return null;
        }
    }

    /**
     * 按 key 拼出最终地址：填占位符；<b>站内相对路径补成本站绝对地址</b>。
     *
     * @return 最终地址；目标不存在 / 被停用 / 模板为空 / 站内地址补不出绝对地址时返回 null
     */
    private String urlOf(StockLink link, String key, String code, String name) {
        if (key == null || key.trim().isEmpty() || link.getTargets() == null) {
            return null;
        }
        for (StockLink.Target t : link.getTargets()) {
            if (t == null || !key.trim().equals(t.getKey()) || !t.isEnabled()) {
                continue;
            }
            String tpl = t.getUrl();
            if (tpl == null || tpl.trim().isEmpty()) {
                return null;
            }
            String u = tpl
                    .replace("{code}", urlencode(code))
                    .replace("{market}", marketOf(code))
                    .replace("{name}", urlencode(name));
            if (u.startsWith("http://") || u.startsWith("https://")) {
                return u;
            }
            String base = site == null ? null : site.base();
            if (base == null || base.isEmpty()) {
                return null; // 拼不出绝对地址的站内页面宁可不给，点了打不开更糟
            }
            return base + (u.startsWith("/") ? u : "/" + u);
        }
        return null;
    }

    /**
     * 与前端 {@code encodeURIComponent} 同口径的编码：空格变 %20 而不是 +，
     * 其余非保留字符保持原样（A股名称是汉字为主，差异只落在空格这类罕见字符上）。
     */
    private static String urlencode(String v) {
        try {
            // JDK 8 没有 encode(String, Charset)，只能用受检异常的字符串重载
            return java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 永远存在，不会走到这
        }
    }

    private void saveRow(String key, String value) {
        saveRow(SCENE, key, value, REMARK);
    }

    private void saveRow(String scene, String key, String value, String remark) {
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SysConfig.SYSTEM_OWNER);
        cfg.setConfigScene(scene);
        cfg.setConfigKey(key);
        cfg.setConfigValue(value == null ? "" : value);
        cfg.setRemark(remark);
        configs.save(cfg);
    }

    private static Set<String> splitKeys(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        for (String s : raw.split(",")) {
            String k = trim(s);
            if (StringUtils.hasText(k)) {
                out.add(k);
            }
        }
        return out;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }
}
