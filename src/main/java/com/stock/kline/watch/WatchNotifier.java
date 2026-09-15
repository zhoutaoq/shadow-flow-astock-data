package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * feat3 推送（一）：项目启动后的「系统信息 + 盯盘名单 + 告警规则」一条钉钉消息，
 * 以及每次盯盘股票 / 告警规则变更后的差异推送。
 *
 * <p>只在配置了 webhook（<code>watch.dingtalk.webhook</code> 非空）时真正发送；
 * 未配置则整块静默，不打扰日志。
 *
 * <p>启动通知再叠一层环境开关：<code>watch.startup-notify.enabled</code>
 * <b>默认 false</b>（本地开发反复重启不发消息），生产发布时用
 * <code>--watch.startup-notify.enabled=true</code> 打开，消息首行标明「生产发布」与版本号。
 *
 * <p>实现要点：
 * <ul>
 *   <li>启动通知挂在 {@link ApplicationReadyEvent} 上，保证所有 bean 就绪、配置已载入后才发；</li>
 *   <li>变更通知通过 {@link WatchConfigStore#addListener} 注册——store 每次落盘更新都会回调，
 *       本类拿"本次快照"与"启动时基线"比对，只有盯盘股票或规则真变了才推差异，纯改 webhook/开关不推；</li>
 *   <li>发送丢到独立单线程守护池里异步执行，不阻塞保存配置的 HTTP 请求。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "watch.enabled", havingValue = "true", matchIfMissing = true)
public class WatchNotifier implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(WatchNotifier.class);

    private final WatchProperties props;
    private final WatchConfigStore store;
    private final DingTalkNotifier notifier;
    private final Environment env;
    private final ExecutorService sendPool;

    /** userId → 该用户上次推送时的名单与规则基线 */
    private final Map<Long, Baseline> baselines = new ConcurrentHashMap<>();

    public WatchNotifier(WatchProperties props, WatchConfigStore store,
                         DingTalkNotifier notifier, Environment env) {
        this.props = props;
        this.store = store;
        this.notifier = notifier;
        this.env = env;
        this.sendPool = Executors.newSingleThreadExecutor(new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "watch-notify-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 一个用户的基线：上次推送时的名单（code → 名称）与规则签名 */
    private static class Baseline {
        final Map<String, String> stocks;
        final List<String> rules;

        Baseline(WatchSettings snap) {
            this.stocks = toNameMap(snap.getStocks());
            this.rules = ruleSignature(snap);
        }
    }

    @PostConstruct
    public void init() {
        // store 的启动 reload 通知发生在更早的 @PostConstruct，这里先记基线、再注册监听，
        // 这样启动那次不当作"变更"，后续页面改名单/规则才被识别为真实变更。
        baselines.put(store.systemUserId(), new Baseline(store.snapshot()));
        store.addListener(this::onStoreChange);
    }

    @PreDestroy
    public void destroy() {
        sendPool.shutdownNow();
    }

    /**
     * 应用就绪后推送启动消息。
     *
     * <p>两个前置条件：<b>启动通知开关打开</b>（{@code watch.startup-notify.enabled}，
     * 本地默认关，生产发布时用命令行参数打开）+ webhook 已配置。
     * 本地开发反复重启不该往群里刷消息，所以默认不下发。
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!props.getStartupNotify().isEnabled()) {
            log.info("[watch] 启动通知未开启（本地默认不发；生产发布用 "
                    + "--watch.startup-notify.enabled=true 打开），本轮跳过");
            return;
        }
        if (!props.getDingtalk().pushEnabled()) {
            log.warn("[watch] 启动通知已开启，但钉钉推送总开关未打开或没有配置 webhook，跳过"
                    + "（--watch.dingtalk.enabled=true 打开总开关）");
            return;
        }
        WatchSettings snap = store.snapshot();
        String version = resolveVersion();
        log.info("[watch] 生产发布启动通知：版本 {}，标的 {} 只", version,
                snap.getStocks() == null ? 0 : snap.getStocks().size());
        // 启动通知是给运维看的系统消息，固定进系统默认群（超管那份配置出口的机器人）
        dispatch(buildStartup(snap, version), "启动通知", store.webhookOf(store.systemUserId()));
    }

    /** store 变更回调：只在与该用户上次基线不同的名单/规则变化时，往他自己的群推差异 */
    private void onStoreChange(long userId, WatchSettings snap) {
        String webhook = store.webhookOf(userId);
        if (webhook.isEmpty()) {
            return;
        }
        Baseline base = baselines.get(userId);
        if (base == null) {
            // 第一次看到这个用户的配置（他刚落了一份）：只记基线，
            // 跟启动通知同一个道理 —— 那不是"变更"，推出去会莫名其妙。
            baselines.put(userId, new Baseline(snap));
            return;
        }
        Map<String, String> now = toNameMap(snap.getStocks());
        List<String> nowRules = ruleSignature(snap);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!base.stocks.containsKey(e.getKey())) {
                added.add(nameLine(e.getKey(), e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : base.stocks.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                removed.add(nameLine(e.getKey(), e.getValue()));
            }
        }
        boolean rulesChanged = !nowRules.equals(base.rules);
        if (added.isEmpty() && removed.isEmpty() && !rulesChanged) {
            return; // 只动了机器人 / 开关 / 间隔，不属于「股票或规则变更」，不推
        }
        baselines.put(userId, new Baseline(snap));

        StringBuilder sb = new StringBuilder("盯盘配置已变更\n");
        if (!added.isEmpty()) {
            sb.append("新增 ").append(added.size()).append(" 只：").append(String.join("、", added)).append('\n');
        }
        if (!removed.isEmpty()) {
            sb.append("移除 ").append(removed.size()).append(" 只：").append(String.join("、", removed)).append('\n');
        }
        sb.append("当前共 ").append(now.size()).append(" 只\n");
        if (rulesChanged) {
            sb.append("告警规则已更新：").append(ruleText(snap)).append('\n');
        }
        dispatch(sb.toString().trim(), "配置变更通知", webhook);
    }

    /** 启动消息：【生产发布】+ 版本号 + IP + 服务地址 + 盯盘名单 + 告警规则 */
    private String buildStartup(WatchSettings snap, String version) {
        List<WatchSettings.StockRef> stocks = snap.getStocks() == null
                ? new ArrayList<>() : snap.getStocks();
        String ip = WatchText.localIp();
        String port = env.getProperty("server.port", "80");
        StringBuilder sb = new StringBuilder("【生产发布】盯盘系统 ").append(version).append(" 已启动\n");
        sb.append("IP：").append(ip).append('\n');
        sb.append("服务地址：http://").append(ip).append(':').append(port).append('\n');
        sb.append("盯盘股票（").append(stocks.size()).append("）：").append(stockText(stocks)).append('\n');
        sb.append("告警规则：").append(ruleText(snap)).append('\n');
        return sb.toString().trim();
    }

    /**
     * 版本号：配置里写了就用配置的；没写就取 jar 清单里的 Implementation-Version
     * （由 maven-jar-plugin 按 pom 的 {@code <version>} 写进去，生产 jar 就是发布版本）；
     * 都取不到（比如从 IDE 直接跑 class）标 dev。
     */
    private String resolveVersion() {
        String cfg = props.getStartupNotify().getVersion();
        if (cfg != null && !cfg.trim().isEmpty()) {
            return cfg.trim();
        }
        Package pkg = WatchNotifier.class.getPackage();
        String fromJar = pkg == null ? null : pkg.getImplementationVersion();
        return fromJar == null || fromJar.trim().isEmpty() ? "dev" : fromJar.trim();
    }

    private void dispatch(String content, String kind, String webhook) {
        sendPool.execute(() -> {
            if (notifier.sendTo(webhook, content)) {
                log.info("[watch] 已推送{}：{}", kind, content);
            } else {
                log.warn("[watch] 钉钉未推送（未配置机器人或调用失败），{}内容：{}", kind, content);
            }
        });
    }

    private String stockText(List<WatchSettings.StockRef> stocks) {
        if (stocks.isEmpty()) {
            return "（无）";
        }
        List<String> lines = new ArrayList<>(stocks.size());
        for (WatchSettings.StockRef s : stocks) {
            String name = s.getName() == null || s.getName().trim().isEmpty() ? s.getCode() : s.getName().trim();
            lines.add(nameLine(s.getCode(), name));
        }
        return String.join("、", lines);
    }

    private String ruleText(WatchSettings snap) {
        List<WatchSettings.Rule> rules = snap.getRules();
        long intervalSec = snap.getIntervalSeconds();
        if (rules == null || rules.isEmpty()) {
            return "（无）";
        }
        List<String> parts = new ArrayList<>(rules.size());
        for (WatchSettings.Rule r : rules) {
            parts.add("过去 " + (r.getIntervals() * intervalSec) + "s 内涨跌 " + WatchText.pct(r.getPercent()) + "%");
        }
        return String.join("；", parts);
    }

    /** 只保留单条规则的时间与阈值签名，排序不影响（规则本身有界 ≤ MAX_RULES） */
    private static List<String> ruleSignature(WatchSettings snap) {
        List<WatchSettings.Rule> rules = snap.getRules();
        List<String> out = new ArrayList<>();
        if (rules != null) {
            for (WatchSettings.Rule r : rules) {
                out.add(r.getIntervals() + "@" + r.getPercent());
            }
        }
        return out;
    }

    private static Map<String, String> toNameMap(List<WatchSettings.StockRef> stocks) {
        Map<String, String> map = new LinkedHashMap<>();
        if (stocks != null) {
            for (WatchSettings.StockRef s : stocks) {
                if (s == null || s.getCode() == null) {
                    continue;
                }
                String name = s.getName() == null || s.getName().trim().isEmpty() ? s.getCode() : s.getName().trim();
                map.put(s.getCode().trim(), name);
            }
        }
        return map;
    }

    private static String nameLine(String code, String name) {
        return name + "(" + code + ")";
    }
}
