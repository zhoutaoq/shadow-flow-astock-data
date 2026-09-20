package io.github.zhoutaoq.shadowflow.biz.service;

import io.github.zhoutaoq.shadowflow.biz.config.StockLinkProperties;
import io.github.zhoutaoq.shadowflow.biz.model.StockLink;
import io.github.zhoutaoq.shadowflow.biz.model.StockLinkSaveRequest;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import io.github.zhoutaoq.shadowflow.biz.repo.SysConfigRepository;
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

    public StockLinkService(StockLinkProperties props, SysConfigRepository configs) {
        this.props = props;
        this.configs = configs;
    }

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
        return assemble(enabled, wantDefault, disabled);
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

        saveRow(KEY_ENABLED, enabled ? "true" : "false");
        saveRow(KEY_DEFAULT, resolved.getDefaultTarget());
        saveRow(KEY_DISABLED, String.join(",", disabled));
        log.info("[stock-link] 股票名称跳转已更新：{}，默认 {}，停用 [{}]",
                enabled ? "启用" : "停用", resolved.getDefaultTarget(), String.join(",", disabled));
        return resolved;
    }

    /** 恢复默认：删掉这几行配置，回到 application.yml 的默认口径 */
    public void reset() {
        for (SysConfig row : configs.list(SysConfig.SYSTEM_OWNER)) {
            if (SCENE.equals(row.getConfigScene()) && row.getId() != null) {
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

    private void saveRow(String key, String value) {
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SysConfig.SYSTEM_OWNER);
        cfg.setConfigScene(SCENE);
        cfg.setConfigKey(key);
        cfg.setConfigValue(value == null ? "" : value);
        cfg.setRemark(REMARK);
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
