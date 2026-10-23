package io.github.zhoutaoq.shadowflow.biz.service;

import io.github.zhoutaoq.shadowflow.biz.model.MenuConfig;
import io.github.zhoutaoq.shadowflow.biz.model.MenuSaveRequest;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import io.github.zhoutaoq.shadowflow.biz.repo.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 菜单配置的读写（需求 v1.6.2 第 1 条）：菜单<b>名称</b>与<b>顺序</b>支持配置。
 *
 * <p>与「股票名称跳转」「首页背景行情」同一套路：配置落系统级配置行
 * （{@code ownerId = 0}，场景 {@code menu}），超管在「系统配置」页点一下即生效。
 *
 * <p><b>代码里的 {@link #BUILT_IN} 是唯一真相源</b>，配置只覆盖两层薄薄的东西：
 * <ul>
 *   <li><b>顺序</b> —— 保存时按提交顺序整体写成一行 {@code order}（key 列表）；</li>
 *   <li><b>名称</b> —— 每个改过名的项一行 {@code label.<key>}。</li>
 * </ul>
 * 地址（href）与可见性（login / admin）都在 {@link #BUILT_IN} 里，<b>不落配置、不允许被改</b>：
 * 前者改坏了就是一个点了 404 的死菜单，后者是权限而不是展示偏好 ——
 * 把「用户管理」的 admin 标记做成可配，等于开了一个越权入口。
 *
 * <p>顺序行里出现内置没有的 key 一律忽略，内置有而顺序行里漏掉的按内置顺序补回队尾：
 * 以后往 {@link #BUILT_IN} 里加一个新菜单，老配置（只写了当时那几个 key）
 * 不会把它挤掉，新菜单默认排在最后、默认可见。
 */
@Service
public class MenuConfigService {

    private static final Logger log = LoggerFactory.getLogger(MenuConfigService.class);

    /** 配置落点：系统级 + 场景 menu */
    private static final String SCENE = "menu";
    /** 顺序行：整条菜单的 key 列表，逗号分隔 */
    private static final String KEY_ORDER = "order";
    /** 名称行的前缀：label.<key> → 自定义名称 */
    private static final String KEY_LABEL_PREFIX = "label.";
    private static final String REMARK = "全站菜单名称与顺序";

    /** 名称长度上限：顶栏是一行 56px 高，超过这个长度会把导航挤到第二行 */
    private static final int MAX_TEXT_LEN = 12;

    /**
     * 内置菜单：<b>key → 名称 / 地址 / 权限</b>，顺序即出厂顺序。
     *
     * <p>与前端 {@code js/common.js} 的 {@code NAV_ITEMS} 一一对应，两边都改才算加了一个菜单。
     * 地址与权限只在这里，配置页拿不到也改不了。
     *
     * <p>v1.7.9：超管那几项（系统配置 / 钉钉推送 / 用户管理 / 账户管理 / 数据迁移）收进
     * 「系统管理」这个分组，作为它的二级菜单 —— 普通用户看不到分组，也就看不到里面的项。
     */
    private static final List<BuiltIn> BUILT_IN = Collections.unmodifiableList(Arrays.asList(
            new BuiltIn("home", "概览", "/"),
            new BuiltIn("stock-list", "股票列表", "/pages/stock-list.html"),
            new BuiltIn("chart", "K线查看", "/pages/chart.html"),
            new BuiltIn("news", "实时资讯", "/pages/news.html"),
            new BuiltIn("backtest", "策略回测", "/pages/backtest.html"),
            new BuiltIn("watch", "盯盘", "/pages/watch.html"),
            new BuiltIn("account", "我的账户", "/pages/account.html"),
            new BuiltIn("feedback", "问题反馈", "/pages/feedback.html"),
            new BuiltIn("admin", "系统管理", null, Arrays.asList(
                    new BuiltIn("system", "系统配置", "/pages/system.html"),
                    new BuiltIn("push", "钉钉推送", "/pages/push.html"),
                    new BuiltIn("users", "用户管理", "/pages/users.html"),
                    // v1.7.9：超管按用户查看账户与持仓，只读、卡片展示，不用代理登录
                    new BuiltIn("admin-accounts", "账户管理", "/pages/admin-accounts.html"),
                    // v1.7.4：超管按维度搬数据（导出 zip / 导入覆盖），入口只对超管可见
                    new BuiltIn("migrate", "数据迁移", "/pages/migrate.html")
            ))
    ));

    private final SysConfigRepository configs;

    public MenuConfigService(SysConfigRepository configs) {
        this.configs = configs;
    }

    /** 当前生效的一份（未配置过时就是内置的名称与顺序，customized = false） */
    public MenuConfig current() {
        String orderRaw = trim(configs.get(SCENE, KEY_ORDER));
        List<String> ordered = resolveOrder(orderRaw);
        boolean customized = StringUtils.hasText(orderRaw);
        MenuConfig out = new MenuConfig();
        List<MenuConfig.Item> items = new ArrayList<>(ordered.size());
        for (String key : ordered) {
            BuiltIn src = builtInOf(key);
            // 二级项不单独出现在顶层：它的位置由所属分组决定（见下面 children 那一段）
            if (src == null || src.parentKey != null) {
                continue;
            }
            MenuConfig.Item it = toItem(src);
            if (!src.children.isEmpty()) {
                List<MenuConfig.Item> kids = new ArrayList<>(src.children.size());
                // 子项顺序同样按 order 行里的相对顺序，order 里缺的按内置顺序补在后面
                for (String k : ordered) {
                    BuiltIn child = builtInOf(k);
                    if (child != null && src.key.equals(child.parentKey)) {
                        kids.add(toItem(child));
                    }
                }
                it.setChildren(kids);
            }
            items.add(it);
        }
        // 改过名也算配置过：只改名不动顺序时不能报「未配置」
        if (!customized) {
            customized = hasAnyLabelRow();
        }
        out.setCustomized(customized);
        out.setItems(items);
        return out;
    }

    /**
     * 保存顺序 + 名称。
     *
     * <p>整份覆盖而不是逐项增量：顺序本来就是「全部元素的一个排列」，
     * 增量写只会让「谁在前」变成一个说不清的状态。名称同理按提交值整份重写 ——
     * 提交里没带的项按内置名称落回，也就自然实现了「恢复默认」。
     *
     * @param req 按目标顺序排列的菜单项
     * @throws IllegalArgumentException 入参为空 / 出现未知 key / 名称超长
     */
    public MenuConfig save(MenuSaveRequest req) {
        if (req == null || req.getItems() == null || req.getItems().isEmpty()) {
            throw new IllegalArgumentException("菜单配置不能为空");
        }
        List<String> submitted = new ArrayList<>();
        Map<String, String> labels = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (MenuSaveRequest.Item it : req.getItems()) {
            if (it == null || !StringUtils.hasText(it.getKey())) {
                continue;
            }
            String key = it.getKey().trim();
            BuiltIn src = builtInOf(key);
            if (src == null) {
                throw new IllegalArgumentException("未知的菜单项：" + key);
            }
            if (!seen.add(key)) {
                // 同一项出现两次，顺序就说不清了 —— 直接拒绝而不是悄悄取第一个
                throw new IllegalArgumentException("菜单项重复：" + src.text);
            }
            submitted.add(key);
            String text = it.getText() == null ? "" : it.getText().trim();
            if (text.length() > MAX_TEXT_LEN) {
                throw new IllegalArgumentException(
                        "菜单名称「" + text + "」太长，最多 " + MAX_TEXT_LEN + " 个字");
            }
            // 空名称 = 这一项用回内置名称，不落 label 行
            if (StringUtils.hasText(text) && !text.equals(src.text)) {
                labels.put(key, text);
            }
        }
        if (submitted.isEmpty()) {
            throw new IllegalArgumentException("菜单配置不能为空");
        }

        // 内置有、提交里漏掉的项补到队尾：新菜单加进代码后老配置不会把它挤没。
        // 二级项同样补（老页面只提交了顶层项的 key），位置由 current() 收回到所属分组里
        for (BuiltIn b : BUILT_IN) {
            if (!seen.contains(b.key)) {
                submitted.add(b.key);
            }
            for (BuiltIn child : b.children) {
                if (!seen.contains(child.key)) {
                    submitted.add(child.key);
                }
            }
        }

        // 名称行先按「全部清掉再写回」整份重写，否则改名后又改回原名会留下一条永远生效的旧行
        clearRows();
        saveRow(KEY_ORDER, String.join(",", submitted));
        for (Map.Entry<String, String> e : labels.entrySet()) {
            saveRow(KEY_LABEL_PREFIX + e.getKey(), e.getValue());
        }
        log.info("[menu] 菜单配置已更新：顺序 [{}]，自定义名称 {} 项",
                String.join(",", submitted), labels.size());
        return current();
    }

    /** 恢复默认：删掉所有菜单配置行，回到代码里的内置名称与顺序 */
    public MenuConfig reset() {
        clearRows();
        log.info("[menu] 菜单配置已恢复默认");
        return current();
    }

    /* ---------------- 内部实现 ---------------- */

    /**
     * 解析顺序：配置行 → key 列表，过滤未知 key、补回漏掉的项。
     *
     * <p>配置为空（没配过 / 刚好被写空）时直接返回内置顺序。
     * 列表是<b>扁平</b>的（分组 key 与它的二级 key 在同一行里），
     * 层级关系由 {@link BuiltIn#parentKey} 决定，不靠位置。
     */
    private List<String> resolveOrder(String raw) {
        List<String> out = new ArrayList<>(BUILT_IN.size());
        if (StringUtils.hasText(raw)) {
            for (String s : raw.split(",")) {
                String k = trim(s);
                if (StringUtils.hasText(k) && builtInOf(k) != null && !out.contains(k)) {
                    out.add(k);
                }
            }
        }
        for (BuiltIn b : BUILT_IN) {
            if (!out.contains(b.key)) {
                out.add(b.key);
            }
            for (BuiltIn child : b.children) {
                if (!out.contains(child.key)) {
                    out.add(child.key);
                }
            }
        }
        return out;
    }

    /** 该项的展示名称：自定义名称优先，没配过用内置的 */
    private String labelOf(BuiltIn src) {
        String custom = trim(configs.get(SCENE, KEY_LABEL_PREFIX + src.key));
        return StringUtils.hasText(custom) ? custom : src.text;
    }

    /** 是否写过任何一条名称行（用于「只改过名」也算配置过） */
    private boolean hasAnyLabelRow() {
        for (BuiltIn b : BUILT_IN) {
            if (StringUtils.hasText(trim(configs.get(SCENE, KEY_LABEL_PREFIX + b.key)))) {
                return true;
            }
            for (BuiltIn child : b.children) {
                if (StringUtils.hasText(trim(configs.get(SCENE, KEY_LABEL_PREFIX + child.key)))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 删掉本场景下所有配置行 */
    private void clearRows() {
        for (SysConfig row : configs.list(SysConfig.SYSTEM_OWNER)) {
            if (SCENE.equals(row.getConfigScene()) && row.getId() != null) {
                configs.delete(SysConfig.SYSTEM_OWNER, row.getId());
            }
        }
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

    private static BuiltIn builtInOf(String key) {
        for (BuiltIn b : BUILT_IN) {
            if (b.key.equals(key)) {
                return b;
            }
            for (BuiltIn child : b.children) {
                if (child.key.equals(key)) {
                    return child;
                }
            }
        }
        return null;
    }

    /** 内置项 → 外发项（名称按配置覆盖） */
    private MenuConfig.Item toItem(BuiltIn src) {
        MenuConfig.Item it = new MenuConfig.Item();
        it.setKey(src.key);
        it.setText(labelOf(src));
        it.setHref(src.href == null ? "" : src.href);
        return it;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    /** 一个内置菜单项：key / 出厂名称 / 地址（地址只回显，不落配置）/ 所属分组 / 二级项 */
    private static final class BuiltIn {
        private final String key;
        private final String text;
        private final String href;
        /** 所属分组的 key；顶层项为 null。分组构造时统一回填，所以不是 final */
        private String parentKey;
        /** 二级项；非空表示这是一个分组，本身没有地址 */
        private final List<BuiltIn> children;

        private BuiltIn(String key, String text, String href) {
            this(key, text, href, Collections.<BuiltIn>emptyList());
        }

        /** 分组：把子项的 parentKey 回填成自己，层级只在构造时确定一次 */
        private BuiltIn(String key, String text, String href, List<BuiltIn> children) {
            this.key = key;
            this.text = text;
            this.href = href;
            this.parentKey = null;
            this.children = children == null ? Collections.<BuiltIn>emptyList() : children;
            for (BuiltIn child : this.children) {
                child.parentKey = key;
            }
        }
    }
}
