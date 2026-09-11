package com.stock.kline.web;

import com.stock.kline.model.ApiResponse;
import com.stock.kline.watch.WatchConfigStore;
import com.stock.kline.watch.WatchProperties;
import com.stock.kline.watch.WatchSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 盯盘配置：进入页面先输密码，之后凭短期令牌读写配置。
 *
 * <p>密码只在 yml 里维护（<code>watch.password</code>），不下发给前端；
 * 校验通过后发一个只存在内存里的令牌，页面拿它读配置、保存配置，
 * 令牌到期或进程重启后需要重新输一次密码。
 *
 * <p>保存动作由 {@link WatchConfigStore} 完成：先改内存（立刻生效）再落盘，
 * 调度器监听到变更会按新的间隔重新排定时任务。
 */
@RestController
@RequestMapping("/api/watch")
public class WatchController {

    private static final Logger log = LoggerFactory.getLogger(WatchController.class);

    /** 令牌有效期：输一次密码管一天，够用了 */
    private static final long TOKEN_TTL_MS = TimeUnit.HOURS.toMillis(8);

    /** 页面要展示的输入范围，与 WatchConfigStore 里的校验保持一致 */
    private static final long MIN_INTERVAL = 5, MAX_INTERVAL = 3600;
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
    private final ConcurrentMap<String, Long> tokens = new ConcurrentHashMap<>();

    public WatchController(WatchProperties props, WatchConfigStore store) {
        this.props = props;
        this.store = store;
    }

    /** 校验密码，通过则返回短期令牌 */
    @PostMapping("/unlock")
    public ApiResponse<Map<String, Object>> unlock(@RequestBody(required = false) Map<String, Object> body) {
        String input = body == null ? null : String.valueOf(body.get("password"));
        String expected = props.getPassword();
        if (input == null || expected == null || !input.equals(expected)) {
            return ApiResponse.fail("密码不正确");
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, System.currentTimeMillis() + TOKEN_TTL_MS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("token", token);
        data.put("expiresInSeconds", TOKEN_TTL_MS / 1000);
        log.info("[watch] 盯盘页面已解锁（令牌有效期 {} 小时）", TOKEN_TTL_MS / 3600000);
        return ApiResponse.ok(data);
    }

    /** 读取当前配置（需要令牌） */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> get(
            @RequestHeader(value = "X-Watch-Token", required = false) String token) {
        if (!valid(token)) {
            return ApiResponse.fail("请先输入盯盘密码");
        }
        return ApiResponse.ok(buildData(store.snapshot()));
    }

    /** 保存配置（需要令牌）：先写内存再落盘 */
    @PutMapping("/config")
    public ApiResponse<Map<String, Object>> save(
            @RequestHeader(value = "X-Watch-Token", required = false) String token,
            @RequestBody(required = false) WatchSettings body) {
        if (!valid(token)) {
            return ApiResponse.fail("请先输入盯盘密码");
        }
        if (body == null) {
            return ApiResponse.fail("配置内容为空");
        }
        return ApiResponse.ok(buildData(store.update(body)), "已保存并生效");
    }

    private boolean valid(String token) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }
        Long expireAt = tokens.get(token.trim());
        if (expireAt == null) {
            return false;
        }
        if (expireAt < System.currentTimeMillis()) {
            tokens.remove(token.trim());
            return false;
        }
        return true;
    }

    private Map<String, Object> buildData(WatchSettings s) {
        Map<String, Object> data = new LinkedHashMap<>();
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
        // 钉钉配置（页面回显）：v1.4 起只有 webhook 一个字段
        Map<String, Object> dt = new LinkedHashMap<>();
        dt.put("webhook", s.getDingtalk() == null || s.getDingtalk().getWebhook() == null
                ? "" : s.getDingtalk().getWebhook());
        data.put("dingtalk", dt);
        // 涨跌停放量阈值（页面回显）：amountWan 单位万元，times 是相对上一间隔的倍数
        data.put("surge", s.getSurge() == null ? new WatchSettings.SurgeCfg() : s.getSurge());
        data.put("stocks", s.getStocks());
        data.put("configFile", store.getFile() == null ? "" : store.getFile().toString());
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
