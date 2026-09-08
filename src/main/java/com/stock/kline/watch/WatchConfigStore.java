package com.stock.kline.watch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 盯盘配置的唯一来源：内存里留一份，落盘一份 JSON。
 *
 * <p>启动时按这个顺序决定配置内容：
 * <ol>
 *   <li>工作目录下的 <code>watch-config.json</code>（本地开发时就是项目根目录）；</li>
 *   <li>yml 里 <code>watch.config-file</code> 指定的绝对路径（服务器上的部署目录）；</li>
 *   <li>都找不到 → 用 yml 的 <code>interval-seconds / rules / dingtalk / enabled</code> 作默认值，
 *       并把老的 <code>watch-list.txt</code> 名单迁移过来，然后落盘一次。</li>
 * </ol>
 *
 * <p>页面保存时先改内存再落盘：内存这步保证「保存完立刻生效」，
 * 落盘这步保证重启后还在。落盘失败不影响本次生效，只记日志。
 *
 * <p><b>v1.2 兼容</b>：老落盘文件只有 <code>thresholdPercent</code>，没有 <code>rules</code>；
 * 解析时把旧字段重塑成 1 条规则，让旧数据无缝迁移。
 */
@Component
public class WatchConfigStore {

    private static final Logger log = LoggerFactory.getLogger(WatchConfigStore.class);

    /** 落盘文件名 */
    private static final String FILE_NAME = "watch-config.json";

    /** 间隔与规则的允许范围，防止页面上填出把进程拖垮的值 */
    private static final long MIN_INTERVAL = 5, MAX_INTERVAL = 3600;
    private static final double MIN_THRESHOLD = 0.01, MAX_THRESHOLD = 20;
    /**
     * 告警规则"过去时长"的允许范围，单位是"上方轮询间隔"的倍数。
     * 至少 1 个周期（必须横跨一次轮询才有"过去"的语义）；最多 60 个周期，避免太久远。
     */
    private static final int MIN_RULE_INTERVALS = 1, MAX_RULE_INTERVALS = 60;
    /** 最多允许的规则条数：UI / 落盘 / 推送合并都要照单全收，太多了没意义 */
    private static final int MAX_RULES = 8;
    /** 盯盘名单上限：每轮并发查一遍，太多既慢又容易被行情接口限流 */
    private static final int MAX_STOCKS = 200;

    private final WatchProperties props;
    private final ObjectProvider<StockNameIndex> indexProvider;
    private final ObjectProvider<WatchListLoader> loaderProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
    private final List<Consumer<WatchSettings>> listeners = new CopyOnWriteArrayList<>();

    private WatchSettings current = new WatchSettings();
    /** 实际读写的落盘路径（页面展示与排查用） */
    private volatile Path file;

    public WatchConfigStore(WatchProperties props,
                            ObjectProvider<StockNameIndex> indexProvider,
                            ObjectProvider<WatchListLoader> loaderProvider) {
        this.props = props;
        this.indexProvider = indexProvider;
        this.loaderProvider = loaderProvider;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新从磁盘载入；启动时与「恢复 yml 默认」时调用 */
    public synchronized void reload() {
        Path p = resolveExisting();
        if (p != null) {
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                WatchSettings loaded = mapper.readValue(json, WatchSettings.class);
                if (loaded != null) {
                    // 启动时把 yml 的钉钉默认值灌进来（落盘里为空时 fallback）
                    applyDingtalkDefaults(loaded);
                    normalize(loaded);
                    current = loaded;
                    // 同步到 props：DingTalkNotifier 读的是 props.getDingtalk()
                    writeDingtalkBack(loaded);
                    file = p;
                    log.info("[watch] 盯盘配置已载入 {}：开关 {}，间隔 {}s，{} 条规则，{} 只标的",
                            p, current.isEnabled(), current.getIntervalSeconds(),
                            current.getRules().size(), current.getStocks().size());
                    notifyListeners();
                    return;
                }
            } catch (Exception e) {
                log.warn("[watch] 盯盘配置文件解析失败 {}：{}，改用 yml 默认值", p, e.getMessage());
            }
        }
        // 走到这里说明还没有落盘文件：用 yml 默认值 + 迁移老名单
        current = defaultsFromProps();
        file = writeTarget();
        migrateLegacyList();
        normalize(current);
        writeDingtalkBack(current);
        persist();
        notifyListeners();
        log.info("[watch] 盯盘配置落盘位置 {}（开关 {}，间隔 {}s，{} 条规则，{} 只标的）",
                file, current.isEnabled(), current.getIntervalSeconds(),
                current.getRules().size(), current.getStocks().size());
    }

    /** 当前配置的一份拷贝，外部改它不会影响内存里的值 */
    public synchronized WatchSettings snapshot() {
        WatchSettings copy = new WatchSettings();
        copy.setEnabled(current.isEnabled());
        copy.setIntervalSeconds(current.getIntervalSeconds());
        List<WatchSettings.Rule> rules = new ArrayList<>(current.getRules().size());
        for (WatchSettings.Rule r : current.getRules()) {
            rules.add(new WatchSettings.Rule(r.getIntervals(), r.getPercent()));
        }
        copy.setRules(rules);
        copy.setDingtalk(copyDingtalk(current.getDingtalk()));
        List<WatchSettings.StockRef> refs = new ArrayList<>(current.getStocks().size());
        for (WatchSettings.StockRef r : current.getStocks()) {
            refs.add(new WatchSettings.StockRef(r.getCode(), r.getName()));
        }
        copy.setStocks(refs);
        return copy;
    }

    /** 当前落盘文件路径 */
    public Path getFile() {
        return file;
    }

    public synchronized boolean isEnabled() {
        return current.isEnabled();
    }

    public synchronized long getIntervalSeconds() {
        return current.getIntervalSeconds();
    }

    /** 多规则快照（外部只读，不要 push 进来） */
    public synchronized List<WatchSettings.Rule> rules() {
        List<WatchSettings.Rule> out = new ArrayList<>(current.getRules().size());
        for (WatchSettings.Rule r : current.getRules()) {
            out.add(new WatchSettings.Rule(r.getIntervals(), r.getPercent()));
        }
        return out;
    }

    /** 盯盘标的（供轮询使用）；名称缺失时按名录补一次 */
    public synchronized List<WatchTarget> targets() {
        List<WatchTarget> list = new ArrayList<>(current.getStocks().size());
        for (WatchSettings.StockRef r : current.getStocks()) {
            if (r == null || r.getCode() == null) {
                continue;
            }
            String code = r.getCode().trim();
            if (!StockNameIndex.isAShare(code)) {
                continue;
            }
            String name = r.getName() == null || r.getName().trim().isEmpty()
                    ? nameOf(code) : r.getName().trim();
            list.add(new WatchTarget(name == null ? code : name, code));
        }
        return list;
    }

    /**
     * 页面保存：先更新内存（立刻生效），再落盘。
     *
     * @return 校验并规范化后的最新配置
     */
    public synchronized WatchSettings update(WatchSettings incoming) {
        if (incoming == null) {
            return snapshot();
        }
        normalize(incoming);
        // 用户没填的钉钉字段从当前内存继承下来（不每次都退回 yml 默认值）
        if (incoming.getDingtalk() == null) {
            incoming.setDingtalk(copyDingtalk(current.getDingtalk()));
        } else {
            mergeDingtalk(incoming.getDingtalk(), current.getDingtalk());
        }
        current = incoming;
        writeDingtalkBack(current);
        persist();
        notifyListeners();
        log.info("[watch] 盯盘配置已更新：开关 {}，间隔 {}s，{} 条规则，{} 只标的",
                current.isEnabled(), current.getIntervalSeconds(),
                current.getRules().size(), current.getStocks().size());
        return snapshot();
    }

    /** 配置变更监听（调度器用它重排定时任务） */
    public void addListener(Consumer<WatchSettings> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    private void notifyListeners() {
        WatchSettings snap = snapshot();
        for (Consumer<WatchSettings> l : listeners) {
            try {
                l.accept(snap);
            } catch (Exception e) {
                log.warn("[watch] 配置变更回调失败: {}", e.getMessage());
            }
        }
    }

    /** 按 yml 默认值构造一份配置（含老名单迁移） */
    private WatchSettings defaultsFromProps() {
        WatchSettings s = new WatchSettings();
        s.setEnabled(props.isEnabled());
        s.setIntervalSeconds(props.getIntervalSeconds());
        s.setRules(defaultRulesFromProps());
        WatchSettings.DingTalkCfg dt = new WatchSettings.DingTalkCfg();
        dt.setWebhook(props.getDingtalk().getWebhook() == null ? "" : props.getDingtalk().getWebhook());
        s.setDingtalk(dt);
        s.setStocks(new ArrayList<>());
        return s;
    }

    /** yml 里没单独写 rules 字段时，给一份保守默认：1 个周期/1% + 5 个周期/2% */
    private List<WatchSettings.Rule> defaultRulesFromProps() {
        List<WatchSettings.Rule> out = new ArrayList<>(2);
        // 兼容老版本：yml 里的 thresholdPercent 仍然作为一条默认规则
        if (props.getThresholdPercent() > 0) {
            out.add(new WatchSettings.Rule(1, props.getThresholdPercent()));
        }
        out.add(new WatchSettings.Rule(5, 2.0));
        return out;
    }

    /** 启动时把 yml 的钉钉默认值灌进 loaded.dingtalk（仅在落盘字段为空时）。v1.4 起只剩 webhook。 */
    private void applyDingtalkDefaults(WatchSettings loaded) {
        WatchSettings.DingTalkCfg dt = loaded.getDingtalk();
        if (dt == null) {
            dt = new WatchSettings.DingTalkCfg();
            loaded.setDingtalk(dt);
        }
        if (isBlank(dt.getWebhook()) && !isBlank(props.getDingtalk().getWebhook())) {
            dt.setWebhook(props.getDingtalk().getWebhook());
        }
    }

    /** 把 loaded 里的钉钉字段回写到 props，DingTalkNotifier.send 读的是 props.getDingtalk() */
    private void writeDingtalkBack(WatchSettings s) {
        if (s.getDingtalk() == null) {
            return;
        }
        // 内存里的值始终代表运行时实际生效的 webhook
        // 空字符串清掉：这样 notifier.configured() 会返回 false（钉钉关）
        props.getDingtalk().setWebhook(s.getDingtalk().getWebhook() == null ? "" : s.getDingtalk().getWebhook());
    }

    /** 合并：用户新提交里为空的字段从老内存里继承，避免把已配的 webhook 又退回 yml 默认 */
    private void mergeDingtalk(WatchSettings.DingTalkCfg in, WatchSettings.DingTalkCfg old) {
        if (old == null) {
            return;
        }
        if (in.getWebhook() == null) {
            in.setWebhook(old.getWebhook() == null ? "" : old.getWebhook());
        }
    }

    private static WatchSettings.DingTalkCfg copyDingtalk(WatchSettings.DingTalkCfg src) {
        WatchSettings.DingTalkCfg out = new WatchSettings.DingTalkCfg();
        if (src != null) {
            out.setWebhook(src.getWebhook() == null ? "" : src.getWebhook());
        }
        return out;
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 首次启动时把 watch-list.txt 里的名单搬进来，避免升级后要重新填一遍 */
    private void migrateLegacyList() {
        WatchListLoader loader = loaderProvider.getIfAvailable();
        if (loader == null) {
            return;
        }
        List<WatchTarget> legacy;
        try {
            legacy = loader.load();
        } catch (Exception e) {
            return;
        }
        if (legacy == null || legacy.isEmpty()) {
            return;
        }
        List<WatchSettings.StockRef> refs = new ArrayList<>(legacy.size());
        for (WatchTarget t : legacy) {
            refs.add(new WatchSettings.StockRef(t.getCode(), t.getName()));
        }
        current.setStocks(refs);
        log.info("[watch] 已从 {} 迁移 {} 只标的到落盘配置", props.getWatchListFile(), refs.size());
    }

    /**
     * 校验并规范化：截断到允许范围、剔除空规则、按 seconds 去重。
     *
     * <p>对落盘文件做向后兼容：发现没有 rules 字段、但有老版 thresholdPercent 时，
     * 把它重塑成 1 条规则塞进 rules 里。完成后 WatchSettings 只有 rules 这一条告警口径。
     */
    private void normalize(WatchSettings s) {
        long interval = s.getIntervalSeconds() < MIN_INTERVAL ? MIN_INTERVAL
                : Math.min(s.getIntervalSeconds(), MAX_INTERVAL);
        s.setIntervalSeconds(interval);

        // 规则：截断、去重、空规则丢弃；时间单位按"上方轮询间隔的倍数"
        List<WatchSettings.Rule> src = s.getRules() == null ? new ArrayList<>() : s.getRules();
        List<WatchSettings.Rule> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        for (WatchSettings.Rule r : src) {
            if (r == null) {
                continue;
            }
            int intervals = r.getIntervals();
            double pct = r.getPercent();
            if (intervals < MIN_RULE_INTERVALS || intervals > MAX_RULE_INTERVALS) {
                continue;
            }
            if (pct < MIN_THRESHOLD || pct > MAX_THRESHOLD) {
                continue;
            }
            if (!seen.add(intervals)) {
                continue;
            }
            out.add(new WatchSettings.Rule(intervals, pct));
            if (out.size() >= MAX_RULES) {
                log.warn("[watch] 告警规则超过 {} 条，多余的已忽略", MAX_RULES);
                break;
            }
        }
        // 至少保留 1 条规则，否则页面会变成"无论怎么动都不告警"的迷惑状态
        if (out.isEmpty()) {
            out.add(new WatchSettings.Rule(1, 1.0));
            log.warn("[watch] 配置里没有合法告警规则，已按默认补一条 1 倍轮询周期/1%");
        }
        s.setRules(out);

        List<WatchSettings.StockRef> stockSrc = s.getStocks() == null
                ? new ArrayList<>() : s.getStocks();
        List<WatchSettings.StockRef> stockOut = new ArrayList<>();
        Set<String> seenStock = new LinkedHashSet<>();
        for (WatchSettings.StockRef r : stockSrc) {
            if (r == null || r.getCode() == null) {
                continue;
            }
            String code = r.getCode().trim();
            if (!StockNameIndex.isAShare(code) || !seenStock.add(code)) {
                continue;
            }
            if (stockOut.size() >= MAX_STOCKS) {
                log.warn("[watch] 盯盘名单超过 {} 只，多余的已忽略", MAX_STOCKS);
                break;
            }
            String name = r.getName() == null ? "" : r.getName().trim();
            if (name.isEmpty()) {
                String resolved = nameOf(code);
                name = resolved == null ? "" : resolved;
            }
            stockOut.add(new WatchSettings.StockRef(code, name));
        }
        s.setStocks(stockOut);
    }

    private String nameOf(String code) {
        StockNameIndex index = indexProvider.getIfAvailable();
        return index == null ? null : index.nameOf(code);
    }

    /** 写盘；失败只记日志，不影响内存里已经生效的配置 */
    private void persist() {
        Path target = file != null ? file : writeTarget();
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(target, writer.writeValueAsString(snapshot()).getBytes(StandardCharsets.UTF_8));
            file = target;
        } catch (IOException e) {
            log.warn("[watch] 盯盘配置落盘失败 {}：{}（内存里的配置仍然生效）", target, e.getMessage());
        }
    }

    /** 已存在的配置文件：先工作目录，再 yml 指定的绝对路径 */
    private Path resolveExisting() {
        Path local = Paths.get(System.getProperty("user.dir", "."), FILE_NAME);
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path configured = configuredPath();
        if (configured != null && Files.isRegularFile(configured)) {
            return configured;
        }
        return null;
    }

    /** 写盘目标：优先工作目录；工作目录不可用时退回 yml 的绝对路径 */
    private Path writeTarget() {
        Path local = Paths.get(System.getProperty("user.dir", "."), FILE_NAME);
        Path parent = local.getParent();
        if (parent == null || Files.isDirectory(parent)) {
            return local;
        }
        Path configured = configuredPath();
        return configured != null ? configured : local;
    }

    private Path configuredPath() {
        String p = props.getConfigFile();
        if (p == null || p.trim().isEmpty()) {
            return null;
        }
        try {
            return Paths.get(p.trim());
        } catch (Exception e) {
            log.warn("[watch] 配置的落盘路径不合法 {}: {}", p, e.getMessage());
            return null;
        }
    }
}
