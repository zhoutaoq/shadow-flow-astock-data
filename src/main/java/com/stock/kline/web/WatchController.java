package com.stock.kline.web;

import com.stock.kline.auth.AuthContext;
import com.stock.kline.model.ApiResponse;
import com.stock.kline.watch.WatchConfigStore;
import com.stock.kline.watch.WatchProperties;
import com.stock.kline.watch.WatchSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 盯盘配置：登录后读写<b>自己那份</b>配置。
 *
 * <p>v1.3 起盯盘配置跟账号走（见 {@link WatchConfigStore}）：
 * 进来的人是谁就读写谁的 <code>./data/watch/watch-{userId}.json</code>。
 *
 * <p>v1.5.2 起每份配置都真的跑起来：超管那份同时是系统默认配置（系统级通知的出口），
 * 其余用户开了盯盘就按自己的间隔被轮询、告警进自己配的机器人。
 *
 * <p>接口面仍然只有「读 / 存」两个动作：保存动作由 store 完成，
 * 先改内存（立刻生效）再落盘，调度器监听到变更会按新的间隔重新排定时任务。
 *
 * <p>原来的「盯盘页面密码」已经取消 —— 需要登录才能进来，密码体系交给了账号。
 */
@RestController
@RequestMapping("/api/watch")
public class WatchController {

    private static final Logger log = LoggerFactory.getLogger(WatchController.class);

    /** 页面要展示的输入范围，与 WatchConfigStore 里的校验保持一致 */
    private static final long MIN_INTERVAL = 3, MAX_INTERVAL = 3600;
    private static final double MIN_THRESHOLD = 0.01, MAX_THRESHOLD = 20;
    /** 告警规则的"过去时长"用轮询间隔的倍数表示，至少 1 倍、最多 60 倍 */
    private static final int MIN_RULE_INTERVALS = 1, MAX_RULE_INTERVALS = 60;
    /** 放量阈值：成交额 1万 ~ 100亿元；倍数 1 ~ 1000 倍 */
    private static final double MIN_SURGE_AMOUNT_WAN = 1, MAX_SURGE_AMOUNT_WAN = 1000000;
    private static final double MIN_SURGE_TIMES = 1, MAX_SURGE_TIMES = 1000;
    private static final int MAX_RULES = 8;
    private static final int MAX_STOCKS = 200;

    private final WatchProperties props;
    private final WatchConfigStore store;

    public WatchController(WatchProperties props, WatchConfigStore store) {
        this.props = props;
        this.store = store;
    }

    /** 读取当前登录用户的配置 */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> get() {
        long userId = requireUserId();
        return ApiResponse.ok(buildData(store.snapshot(userId), userId));
    }

    /** 保存当前登录用户的配置：先写内存再落盘 */
    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> save(@RequestBody(required = false) WatchSettings body) {
        long userId = requireUserId();
        if (body == null) {
            return ApiResponse.fail("配置内容为空");
        }
        return ApiResponse.ok(buildData(store.update(userId, body), userId), "已保存并生效");
    }

    /** 盯盘总开关的当前状态：整块功能被 yml 关掉时页面要给出说明而不是白屏 */
    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("moduleEnabled", props.isEnabled());
        return ApiResponse.ok(data);
    }

    /**
     * 当前登录用户。
     *
     * <p>接口走的是 {@link AuthContext}（拦截器已保证登录），
     * 这里再判一次是为了防止以后有人把路径从拦截器里摘掉后拿到「0 号用户」的配置。
     */
    private static long requireUserId() {
        long userId = AuthContext.currentUserId();
        if (userId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }

    private Map<String, Object> buildData(WatchSettings s, long userId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ownerId", userId);
        data.put("isSystemDefault", AuthContext.isAdmin());
        data.put("enabled", s.isEnabled());
        data.put("intervalSeconds", s.getIntervalSeconds());
        // 多规则（每条：intervals 倍的轮询间隔 + percent）
        List<Map<String, Object>> rules = new ArrayList<>(s.getRules().size());
        for (WatchSettings.Rule r : s.getRules()) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("intervals", r.getIntervals());
            one.put("percent", r.getPercent());
            rules.add(one);
        }
        data.put("rules", rules);
        // 钉钉配置（页面回显）：v1.4 起只有 webhook 一个字段；
        // v1.5.2 起附一个「这条配置推给谁」的状态，页面据此显示推送目标
        Map<String, Object> dt = new LinkedHashMap<>();
        dt.put("webhook", s.getDingtalk() == null || s.getDingtalk().getWebhook() == null
                ? "" : s.getDingtalk().getWebhook());
        dt.put("ownGroup", store.hasOwnWebhook(userId));
        dt.put("targetConfigured", !store.webhookOf(userId).isEmpty());
        data.put("dingtalk", dt);
        // 涨跌停放量阈值（页面回显）：amountWan 单位万元，times 是相对上一间隔的倍数
        data.put("surge", s.getSurge() == null ? new WatchSettings.SurgeCfg() : s.getSurge());
        data.put("stocks", s.getStocks());
        data.put("configFile", store.getFile(userId) == null ? "" : store.getFile(userId).toString());
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("minInterval", MIN_INTERVAL);
        limits.put("maxInterval", MAX_INTERVAL);
        limits.put("minThreshold", MIN_THRESHOLD);
        limits.put("maxThreshold", MAX_THRESHOLD);
        limits.put("minRuleIntervals", MIN_RULE_INTERVALS);
        limits.put("maxRuleIntervals", MAX_RULE_INTERVALS);
        limits.put("maxRules", MAX_RULES);
        limits.put("maxStocks", MAX_STOCKS);
        limits.put("minSurgeAmountWan", MIN_SURGE_AMOUNT_WAN);
        limits.put("maxSurgeAmountWan", MAX_SURGE_AMOUNT_WAN);
        limits.put("minSurgeTimes", MIN_SURGE_TIMES);
        limits.put("maxSurgeTimes", MAX_SURGE_TIMES);
        data.put("limits", limits);
        return data;
    }
}
