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

    /** 启动时的盯盘名单基线：code → 名称 */
    private volatile Map<String, String> baselineStocks = new LinkedHashMap<>();
    /** 启动时的告警规则基线：逐条 intervals@percent，保序 */
    private volatile List<String> baselineRules = new ArrayList<>();

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

    @PostConstruct
    public void init() {
        // store 的启动 reload 通知发生在更早的 @PostConstruct，这里先记基线、再注册监听，
        // 这样启动那次不当作"变更"，后续页面改名单/规则才被识别为真实变更。
        WatchSettings snap = store.snapshot();
        baselineStocks = toNameMap(snap.getStocks());
        baselineRules = ruleSignature(snap);
        store.addListener(this::onStoreChange);
    }

    @PreDestroy
    public void destroy() {
        sendPool.shutdownNow();
    }

    /** 应用就绪后推送启动消息（webhook 未配置则跳过） */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!props.getDingtalk().configured()) {
            return;
        }
        WatchSettings snap = store.snapshot();
        String msg = buildStartup(snap);
        dispatch(msg, "启动通知");
    }

    /** store 变更回调：只在与启动基线不同的名单/规则变化时推送差异 */
    private void onStoreChange(WatchSettings snap) {
        if (!props.getDingtalk().configured()) {
            return;
        }
        Map<String, String> now = toNameMap(snap.getStocks());
        List<String> nowRules = ruleSignature(snap);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (Map.Entry<String, String> e : now.entrySet()) {
            if (!baselineStocks.containsKey(e.getKey())) {
                added.add(nameLine(e.getKey(), e.getValue()));
            }
        }
        for (Map.Entry<String, String> e : baselineStocks.entrySet()) {
            if (!now.containsKey(e.getKey())) {
                removed.add(nameLine(e.getKey(), e.getValue()));
            }
        }
        boolean rulesChanged = !nowRules.equals(baselineRules);
        if (added.isEmpty() && removed.isEmpty() && !rulesChanged) {
            return; // 只动了 webhook / 开关 / 间隔，不属于「股票或规则变更」，不推
        }
        baselineStocks = now;
        baselineRules = nowRules;

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
        dispatch(sb.toString().trim(), "配置变更通知");
    }

    /** 启动消息：IP + 服务地址 + 盯盘名单 + 告警规则 */
    private String buildStartup(WatchSettings snap) {
        List<WatchSettings.StockRef> stocks = snap.getStocks() == null
                ? new ArrayList<>() : snap.getStocks();
        String ip = WatchText.localIp();
        String port = env.getProperty("server.port", "80");
        StringBuilder sb = new StringBuilder("盯盘系统已启动\n");
        sb.append("IP：").append(ip).append('\n');
        sb.append("服务地址：http://").append(ip).append(':').append(port).append('\n');
        sb.append("盯盘股票（").append(stocks.size()).append("）：").append(stockText(stocks)).append('\n');
        sb.append("告警规则：").append(ruleText(snap)).append('\n');
        return sb.toString().trim();
    }

    private void dispatch(String content, String kind) {
        sendPool.execute(() -> {
            if (notifier.send(content)) {
                log.info("[watch] 已推送{}：{}", kind, content);
            } else {
                log.warn("[watch] 钉钉未推送（webhook 未配置或调用失败），{}内容：{}", kind, content);
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
