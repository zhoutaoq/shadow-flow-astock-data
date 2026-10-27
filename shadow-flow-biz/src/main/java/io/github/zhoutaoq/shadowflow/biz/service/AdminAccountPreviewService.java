package io.github.zhoutaoq.shadowflow.biz.service;

import io.github.zhoutaoq.shadowflow.biz.account.AccountOverview;
import io.github.zhoutaoq.shadowflow.biz.account.AccountService;
import io.github.zhoutaoq.shadowflow.biz.account.AccountView;
import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.auth.model.UserView;
import io.github.zhoutaoq.shadowflow.biz.auth.repo.UserRepository;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import io.github.zhoutaoq.shadowflow.biz.repo.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「账户管理」页的默认预览用户配置与平铺数据（v1.8.0）。
 *
 * <p>配置落系统级配置行（{@code ownerId = 0}，场景 {@code admin-accounts}，键
 * {@code preview-users}），与菜单配置同一套路：全局一份、保存即生效，不用重启。
 * 值是一串 userId（逗号分隔），<b>顺序即平铺顺序</b>。
 *
 * <p>平铺只读：每个用户都走 {@link AccountService#overviewReadOnly}，
 * 对方没有账户就返回空，不替他建默认账 —— 看一眼不该改别人的数据。
 *
 * <p>当日收益率按需求指定口径：<b>当日收益 / 持仓市值</b>（不是页面上那个以
 * 「当日基准市值」为分母的百分比）。持仓市值为 0 时给 null，页面显示「—」。
 */
@Service
public class AdminAccountPreviewService {

    private static final Logger log = LoggerFactory.getLogger(AdminAccountPreviewService.class);

    /** 配置落点：系统级 + 场景 admin-accounts */
    private static final String SCENE = "admin-accounts";
    private static final String KEY_USERS = "preview-users";
    private static final String REMARK = "账户管理：默认预览用户";

    /**
     * 上限：每多一个用户就多一轮行情取数，页面 3 秒刷一次，
     * 不设边的话一次平铺能把行情服务打满。
     */
    private static final int MAX_USERS = 20;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final SysConfigRepository configs;
    private final AccountService accounts;
    private final UserRepository users;

    public AdminAccountPreviewService(SysConfigRepository configs, AccountService accounts,
                                      UserRepository users) {
        this.configs = configs;
        this.accounts = accounts;
        this.users = users;
    }

    /** 已配置的预览用户 id（按配置顺序；id 已不存在的仍返回，由调用方过滤显示） */
    public List<Long> previewUserIds() {
        String raw = trim(configs.get(SCENE, KEY_USERS));
        List<Long> out = new ArrayList<>();
        if (!StringUtils.hasText(raw)) {
            return out;
        }
        for (String s : raw.split(",")) {
            String v = s == null ? "" : s.trim();
            if (v.isEmpty()) {
                continue;
            }
            try {
                long id = Long.parseLong(v);
                if (id > 0 && !out.contains(id)) {
                    out.add(id);
                }
            } catch (NumberFormatException e) {
                // 配置行被人手改坏了个别段：忽略这一段，别让整个页面打不开
            }
        }
        return out;
    }

    /** 已配置的预览用户（过滤掉已删除的账号） */
    public List<UserView> previewUsers() {
        List<UserView> out = new ArrayList<>();
        for (Long id : previewUserIds()) {
            User u = users.findById(id);
            if (u != null) {
                out.add(UserView.of(u));
            }
        }
        return out;
    }

    /**
     * 保存默认预览用户（整份覆盖）。
     *
     * @param ids 目标用户 id；空列表 = 清空配置（页面回到「按单个用户查看」）
     * @throws IllegalArgumentException 用户不存在 / 超过上限
     */
    public List<UserView> savePreview(List<Long> ids) {
        List<Long> clean = new ArrayList<>();
        if (ids != null) {
            for (Long id : ids) {
                if (id == null || id <= 0 || clean.contains(id)) {
                    continue;
                }
                if (users.findById(id) == null) {
                    throw new IllegalArgumentException("用户不存在：" + id);
                }
                clean.add(id);
            }
        }
        if (clean.size() > MAX_USERS) {
            throw new IllegalArgumentException("默认预览最多 " + MAX_USERS + " 个用户");
        }
        StringBuilder sb = new StringBuilder();
        for (Long id : clean) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SysConfig.SYSTEM_OWNER);
        cfg.setConfigScene(SCENE);
        cfg.setConfigKey(KEY_USERS);
        cfg.setConfigValue(sb.toString());
        cfg.setRemark(REMARK);
        configs.save(cfg);
        log.info("[account-admin] 默认预览用户已更新：{}", clean.isEmpty() ? "（清空）" : sb);
        return previewUsers();
    }

    /**
     * 平铺视图：配置里所有用户的所有账户，每本账一条。
     *
     * <p>返回结构：
     * <ul>
     *   <li>{@code users} —— 每个用户的合计（账户数 / 总资产 / 当日收益 / 当日收益率）；</li>
     *   <li>{@code cards} —— 每本账一条，带 {@code userId / username}，页面直接摆卡片；</li>
     *   <li>顶层再给一份全部合计，用于页头摘要。</li>
     * </ul>
     */
    public Map<String, Object> board() {
        List<UserView> targets = previewUsers();
        List<Map<String, Object>> cards = new ArrayList<>();
        List<Map<String, Object>> userRows = new ArrayList<>();

        BigDecimal sumInit = BigDecimal.ZERO;
        BigDecimal sumCash = BigDecimal.ZERO;
        BigDecimal sumPosition = BigDecimal.ZERO;
        BigDecimal sumTotal = BigDecimal.ZERO;
        BigDecimal sumDay = BigDecimal.ZERO;
        BigDecimal sumProfit = BigDecimal.ZERO;
        int sumPositionCount = 0;

        for (UserView u : targets) {
            AccountOverview ov = accounts.overviewReadOnly(u.getId());
            BigDecimal uDay = nz(ov.getDayProfit());
            BigDecimal uPosition = nz(ov.getPositionValue());

            for (AccountView a : ov.getAccounts()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("userId", u.getId());
                c.put("username", u.getUsername());
                c.put("accountId", a.getId());
                c.put("accountName", a.getName());
                c.put("initValue", nz(a.getInitValue()));
                c.put("cash", nz(a.getCash()));
                c.put("positionValue", nz(a.getPositionValue()));
                c.put("totalValue", nz(a.getTotalValue()));
                c.put("dayProfit", nz(a.getDayProfit()));
                // 需求口径：当日收益 / 持仓市值；没有持仓时不给数字
                c.put("dayProfitRate", rate(a.getDayProfit(), a.getPositionValue()));
                c.put("totalProfit", nz(a.getTotalProfit()));
                c.put("totalProfitPercent", a.getTotalProfitPercent());
                c.put("positionCount", a.getPositionCount());
                cards.add(c);
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("userId", u.getId());
            row.put("username", u.getUsername());
            row.put("accountCount", ov.getAccountCount());
            row.put("initValue", nz(ov.getInitValue()));
            row.put("cash", nz(ov.getCash()));
            row.put("positionValue", uPosition);
            row.put("totalValue", nz(ov.getTotalValue()));
            row.put("dayProfit", uDay);
            row.put("dayProfitRate", rate(uDay, uPosition));
            row.put("totalProfit", nz(ov.getTotalProfit()));
            row.put("positionCount", ov.getPositionCount());
            userRows.add(row);

            sumInit = sumInit.add(nz(ov.getInitValue()));
            sumCash = sumCash.add(nz(ov.getCash()));
            sumPosition = sumPosition.add(uPosition);
            sumTotal = sumTotal.add(nz(ov.getTotalValue()));
            sumDay = sumDay.add(uDay);
            sumProfit = sumProfit.add(nz(ov.getTotalProfit()));
            sumPositionCount += ov.getPositionCount();
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("users", userRows);
        out.put("cards", cards);
        out.put("accountCount", cards.size());
        out.put("initValue", sumInit);
        out.put("cash", sumCash);
        out.put("positionValue", sumPosition);
        out.put("totalValue", sumTotal);
        out.put("dayProfit", sumDay);
        out.put("dayProfitRate", rate(sumDay, sumPosition));
        out.put("totalProfit", sumProfit);
        out.put("positionCount", sumPositionCount);
        return out;
    }

    /** 当日收益率（%）= 当日收益 / 持仓市值；分母为 0 时给 null */
    private static BigDecimal rate(BigDecimal profit, BigDecimal positionValue) {
        BigDecimal den = nz(positionValue);
        if (den.signum() <= 0) {
            return null;
        }
        return nz(profit).multiply(HUNDRED).divide(den, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }
}
