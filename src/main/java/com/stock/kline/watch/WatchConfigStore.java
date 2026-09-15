package com.stock.kline.watch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.stock.kline.auth.AuthService;
import com.stock.kline.store.DataDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 盯盘配置的唯一来源：<b>每个用户一份</b>，内存里留一份，落盘一份 JSON。
 *
 * <p>v1.3 起盯盘配置跟着账号走（SaaS 化的第一步）：
 * <ul>
 *   <li>落盘位置 {@code ./data/watch/watch-{userId}.json}；</li>
 *   <li>超管那份同时是「系统默认配置」：系统级推送（启动通知、用户反馈兜底）
 *       与未自配机器人的用户都走它的 webhook；</li>
 *   <li>老版本的 {@code watch-config.json} 在首次启动时自动迁成超管的配置。</li>
 * </ul>
 *
 * <p>取配置的顺序（对每个用户）：
 * <ol>
 *   <li>{@code ./data/watch/watch-{userId}.json}；</li>
 *   <li>仅超管：<code>./data/watch-config.json</code> → 工作目录下的
 *       <code>watch-config.json</code> → yml 里 <code>watch.config-file</code>；</li>
 *   <li>都找不到 → 用 yml 的 <code>interval-seconds / rules / dingtalk / enabled</code>
 *       作默认值（超管还会把老的 <code>watch-list.txt</code> 名单迁过来）。</li>
 * </ol>
 *
 * <p>写盘目标恒为 {@code ./data/watch}，读到了旧位置的文件会在首次保存后自动迁过来。
 *
 * <p>页面保存时先改内存再落盘：内存这步保证「保存完立刻生效」，
 * 落盘这步保证重启后还在。落盘失败不影响本次生效，只记日志。
 *
 * <p>v1.5.2 起<b>每个配置独立生效</b>：不是只有超管那一份被后台轮询，
 * 谁开了盯盘、谁配了名单，谁就按自己的节奏被轮询，告警进自己配的机器人 ——
 * 所以本类对外按 {@code userId} 提供取用接口（{@link #activeUserIds()} 是入口）。
 *
 * <p>v1.2 兼容：老落盘文件只有 <code>thresholdPercent</code>，没有 <code>rules</code>；
 * 解析时把旧字段重塑成 1 条规则，让旧数据无缝迁移。
 */
@Component
public class WatchConfigStore {

    private static final Logger log = LoggerFactory.getLogger(WatchConfigStore.class);

    /** 每个用户一个落盘文件：watch/watch-{userId}.json */
    private static final String FILE_PREFIX = "watch/watch-";
    private static final String FILE_SUFFIX = ".json";
    /** 落盘文件的文件名前缀（不含目录），扫目录时用它认「哪些用户配过盯盘」 */
    private static final String FILE_NAME_PREFIX = "watch-";
    /** 老版本（v1.2 / v1.3 早期）的落盘文件名，作为超管配置的迁移来源 */
    private static final String LEGACY_FILE_NAME = "watch-config.json";
    /** admin 账号还没建出来时的兜底 id：超管总是第一个用户 */
    private static final long FALLBACK_ADMIN_ID = 1L;

    /**
     * 间隔与规则的允许范围，防止页面上填出把进程拖垮的值。
     *
     * <p>下限 3s：新浪行情接口本身是秒级刷新的，再快只会重复取到同一笔快照，
     * 白白消耗接口配额、把名单里的标的查重。
     */
    private static final long MIN_INTERVAL = 3, MAX_INTERVAL = 3600;
    private static final double MIN_THRESHOLD = 0.01, MAX_THRESHOLD = 20;
    /**
     * 告警规则"过去时长"的允许范围，单位是"上方轮询间隔"的倍数。
     * 至少 1 个周期（必须横跨一次轮询才有"过去"的语义）；最多 60 个周期，避免太久远。
     */
    private static final int MIN_RULE_INTERVALS = 1, MAX_RULE_INTERVALS = 60;
    /** 放量阈值范围：成交额 1万 ~ 100亿元；倍数 1 ~ 1000 倍 */
    private static final double MIN_SURGE_AMOUNT_WAN = 1, MAX_SURGE_AMOUNT_WAN = 1000000;
    private static final double MIN_SURGE_TIMES = 1, MAX_SURGE_TIMES = 1000;
    /** 最多允许的规则条数：UI / 落盘 / 推送合并都要照单全收，太多了没意义 */
    private static final int MAX_RULES = 8;
    /** 盯盘名单上限：每轮并发查一遍，太多既慢又容易被行情接口限流 */
    private static final int MAX_STOCKS = 200;

    private final WatchProperties props;
    private final DataDir dataDir;
    /** 依赖它不只是为了取 id：注入进来才能保证「超管账号已建好」发生在解析配置归属之前 */
    private final AuthService authService;
    private final ObjectProvider<StockNameIndex> indexProvider;
    private final ObjectProvider<WatchListLoader> loaderProvider;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectWriter writer = new ObjectMapper().writerWithDefaultPrettyPrinter();
    private final List<ChangeListener> listeners = new CopyOnWriteArrayList<>();

    /** userId → 该用户的配置 */
    private final ConcurrentMap<Long, WatchSettings> byUser = new ConcurrentHashMap<>();
    /** 超管 id（系统默认配置归属），解析到后缓存 */
    private volatile Long adminId;

    public WatchConfigStore(WatchProperties props, DataDir dataDir, AuthService authService,
                            ObjectProvider<StockNameIndex> indexProvider,
                            ObjectProvider<WatchListLoader> loaderProvider) {
        this.props = props;
        this.dataDir = dataDir;
        this.authService = authService;
        this.indexProvider = indexProvider;
        this.loaderProvider = loaderProvider;
    }

    /**
     * 配置变更回调：<b>谁改的</b>和改成什么一起给出来。
     *
     * <p>v1.5.2 之前只有一个（超管的）配置会被后台消费，回调不带用户也能用；
     * 现在每个用户的变更都要各自落地 —— 调度器要按各自间隔重排、
     * 通知器要往各自的群推变更说明，所以用户 id 是必需的。
     */
    @FunctionalInterface
    public interface ChangeListener {
        void onChanged(long userId, WatchSettings settings);
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新从磁盘载入全部已加载的配置；启动时与「恢复 yml 默认」时调用 */
    public synchronized void reload() {
        byUser.clear();
        long uid = defaultUserId();
        WatchSettings s = loadOrDefaults(uid);
        byUser.put(uid, s);
        // 系统级推送（启动通知、用户反馈兜底）认超管那份的 webhook
        writeDingtalkBack(s);
        notifyListeners(uid, copy(s));
        log.info("[watch] 系统默认盯盘配置（{}）已载入 {}：开关 {}，间隔 {}s，{} 条规则，{} 只标的",
                authService.adminUsername(), fileOf(uid), s.isEnabled(), s.getIntervalSeconds(),
                s.getRules().size(), s.getStocks().size());
    }

    /* ---------------- 哪些用户在盯盘（后台轮询的入口） ---------------- */

    /**
     * 需要后台轮询的用户：<b>开了盯盘开关、且名单非空</b>的那些，按 id 升序，超管恒在首位。
     *
     * <p>「配过盯盘的用户」以 <code>./data/watch/watch-{id}.json</code> 是否存在为准 ——
     * 不能拿全站用户列表去逐个 {@link #get(long)}，那会给没配过的用户凭空落一份空配置。
     *
     * <p>超管那份单独补进来：它即使名单为空也要参与，
     * 因为它同时是系统级推送（启动通知 / 用户反馈）的出口。
     */
    public synchronized List<Long> activeUserIds() {
        long admin = defaultUserId();
        Set<Long> ids = new LinkedHashSet<>();
        for (long id : userIdsOnDisk()) {
            WatchSettings s = get(id);
            if (s.isEnabled() && !s.getStocks().isEmpty()) {
                ids.add(id);
            }
        }
        if (get(admin).isEnabled()) {
            ids.add(admin);
        }
        List<Long> out = new ArrayList<>(ids);
        Collections.sort(out);
        // 超管排最前：它是系统默认那份，日志与推送顺序上都该先出现
        out.remove(admin);
        out.add(0, admin);
        return out;
    }

    /** 有独立盯盘配置的用户是否至少有一个在运行 */
    public synchronized boolean anyEnabled() {
        for (long id : activeUserIds()) {
            if (get(id).isEnabled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 所有运行中的用户里最短的轮询间隔，作为调度器的基础节拍。
     *
     * <p>每个用户可以有自己的间隔，调度器只按这个最短值醒来，
     * 具体某一轮轮到谁，由 {@code PriceWatchTask} 按各自的间隔自行判断。
     */
    public synchronized long baseIntervalSeconds() {
        long min = -1;
        for (long id : activeUserIds()) {
            long v = Math.max(MIN_INTERVAL, get(id).getIntervalSeconds());
            if (min < 0 || v < min) {
                min = v;
            }
        }
        return min < 0 ? Math.max(MIN_INTERVAL, get(defaultUserId()).getIntervalSeconds()) : min;
    }

    /** 扫描落盘目录，认出所有配过盯盘的用户 id（文件不存在就代表没配过） */
    private List<Long> userIdsOnDisk() {
        List<Long> out = new ArrayList<>();
        Path dir = dataDir.root().resolve("watch");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, FILE_NAME_PREFIX + "*.json")) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                String mid = name.substring(FILE_NAME_PREFIX.length(), name.length() - FILE_SUFFIX.length());
                if (mid.isEmpty() || !mid.chars().allMatch(Character::isDigit)) {
                    continue;
                }
                try {
                    out.add(Long.parseLong(mid));
                } catch (NumberFormatException ignore) {
                    // 文件名里数字超长，不可能是合法用户 id，跳过
                }
            }
        } catch (IOException e) {
            log.warn("[watch] 扫描盯盘配置目录失败 {}：{}", dir, e.getMessage());
        }
        return out;
    }

    /* ---------------- 系统默认配置 = 超管的配置 ---------------- */

    /** 系统默认配置归属的用户 id（超管）；系统级通知（启动通知）就推这个人的群 */
    public long systemUserId() {
        return defaultUserId();
    }

    /** 系统默认配置（超管的）快照 */
    public synchronized WatchSettings snapshot() {
        return snapshot(defaultUserId());
    }

    public synchronized boolean isEnabled() {
        return get(defaultUserId()).isEnabled();
    }

    public synchronized long getIntervalSeconds() {
        return get(defaultUserId()).getIntervalSeconds();
    }

    /** 多规则快照（外部只读，不要 push 进来） */
    public synchronized List<WatchSettings.Rule> rules() {
        return rulesOf(defaultUserId());
    }

    /** 放量阈值的快照（外部只读，不要 push 进来） */
    public synchronized WatchSettings.SurgeCfg surge() {
        return surgeOf(defaultUserId());
    }

    /** 盯盘标的（供轮询使用）；名称缺失时按名录补一次 */
    public synchronized List<WatchTarget> targets() {
        return targetsOf(defaultUserId());
    }

    /** 系统默认配置的落盘路径（页面展示与排查用） */
    public Path getFile() {
        return getFile(defaultUserId());
    }

    /* ---------------- 按用户取用的只读快照 ---------------- */

    /** 某个用户此刻在跑的盯盘标的；名称缺失时按名录补一次 */
    public synchronized List<WatchTarget> targetsOf(long userId) {
        return toTargets(get(userId));
    }

    public synchronized boolean enabledOf(long userId) {
        return get(userId).isEnabled();
    }

    public synchronized long intervalOf(long userId) {
        return get(userId).getIntervalSeconds();
    }

    /** 某个用户的告警规则快照（外部只读，不要 push 进来） */
    public synchronized List<WatchSettings.Rule> rulesOf(long userId) {
        List<WatchSettings.Rule> out = new ArrayList<>();
        for (WatchSettings.Rule r : get(userId).getRules()) {
            out.add(new WatchSettings.Rule(r.getIntervals(), r.getPercent()));
        }
        return out;
    }

    /** 某个用户的放量阈值快照 */
    public synchronized WatchSettings.SurgeCfg surgeOf(long userId) {
        return copySurge(get(userId).getSurge());
    }

    /**
     * 这个用户的告警该推到哪：本人配了机器人就用本人的，没配才落到系统默认那个。
     *
     * <p>不配也照推是为了不让告警凭空消失 —— 页面上的「钉钉机器人」留空就是「用默认群」的意思。
     */
    public synchronized String webhookOf(long userId) {
        String own = get(userId).getDingtalk() == null ? null : get(userId).getDingtalk().getWebhook();
        if (own != null && !own.trim().isEmpty()) {
            return own.trim();
        }
        String sys = props.getDingtalk().getWebhook();
        return sys == null || sys.trim().isEmpty() ? "" : sys.trim();
    }

    /** 这个用户是否配了自己的机器人（页面据此显示推送目标） */
    public synchronized boolean hasOwnWebhook(long userId) {
        String own = get(userId).getDingtalk() == null ? null : get(userId).getDingtalk().getWebhook();
        return own != null && !own.trim().isEmpty();
    }

    /* ---------------- 按用户读写 ---------------- */

    /** 当前配置的一份拷贝，外部改它不会影响内存里的值 */
    public synchronized WatchSettings snapshot(long userId) {
        return copy(get(userId));
    }

    /**
     * 保存某个用户的配置：先更新内存（立刻生效），再落盘。
     *
     * <p>超管那份额外回写全局 webhook（它是系统级推送的出口）。
     * 任何用户的变更都会通知监听方 —— 调度器按新的基础节拍重排、
     * 通知器把变更说明推到<b>改配置那个人自己的群</b>。
     *
     * @return 校验并规范化后的最新配置
     */
    public synchronized WatchSettings update(long userId, WatchSettings incoming) {
        if (incoming == null) {
            return snapshot(userId);
        }
        normalize(incoming);
        WatchSettings prev = get(userId);
        // 用户没填的钉钉字段从当前内存继承下来（不每次都退回 yml 默认值）
        if (incoming.getDingtalk() == null) {
            incoming.setDingtalk(copyDingtalk(prev.getDingtalk()));
        } else {
            mergeDingtalk(incoming.getDingtalk(), prev.getDingtalk());
        }
        boolean systemDefault = userId == defaultUserId();
        byUser.put(userId, incoming);
        if (systemDefault) {
            writeDingtalkBack(incoming);
        }
        persist(userId, incoming);
        notifyListeners(userId, copy(incoming));
        log.info("[watch] 用户 {} 的盯盘配置已更新：开关 {}，间隔 {}s，{} 条规则，{} 只标的",
                userId, incoming.isEnabled(), incoming.getIntervalSeconds(),
                incoming.getRules().size(), incoming.getStocks().size());
        return copy(incoming);
    }

    /** 某个用户配置的落盘路径 */
    public Path getFile(long userId) {
        return fileOf(userId);
    }

    /** 配置变更监听（调度器用它重排定时任务，通知器用它推变更说明） */
    public void addListener(ChangeListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /* ---------------- 内部实现 ---------------- */

    /** 取（必要时载入）某用户的配置 */
    private synchronized WatchSettings get(long userId) {
        WatchSettings s = byUser.get(userId);
        if (s != null) {
            return s;
        }
        s = loadOrDefaults(userId);
        byUser.put(userId, s);
        return s;
    }

    private WatchSettings loadOrDefaults(long userId) {
        Path p = resolveExisting(userId);
        if (p != null) {
            try {
                String json = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                WatchSettings loaded = mapper.readValue(json, WatchSettings.class);
                if (loaded != null) {
                    // 启动时把 yml 的钉钉默认值灌进来（落盘里为空时 fallback）
                    applyDingtalkDefaults(loaded);
                    normalize(loaded);
                    log.info("[watch] 用户 {} 的盯盘配置已载入 {}", userId, p);
                    return loaded;
                }
            } catch (Exception e) {
                log.warn("[watch] 盯盘配置文件解析失败 {}：{}，改用 yml 默认值", p, e.getMessage());
            }
        }
        // 走到这里说明还没有落盘文件：用 yml 默认值 + 迁移老名单
        WatchSettings s = defaultsFromProps();
        if (userId == defaultUserId()) {
            migrateLegacyList(s);
        }
        normalize(s);
        persist(userId, s);
        log.info("[watch] 用户 {} 的盯盘配置落盘位置 {}（开关 {}，间隔 {}s，{} 条规则，{} 只标的）",
                userId, fileOf(userId), s.isEnabled(), s.getIntervalSeconds(),
                s.getRules().size(), s.getStocks().size());
        return s;
    }

    /** 系统默认配置归属的用户：超管。账号还没建好时退回 1（admin 总是第一个用户） */
    private long defaultUserId() {
        Long cached = adminId;
        if (cached != null) {
            return cached;
        }
        long id = authService.adminId();
        if (id > 0) {
            adminId = id;
            return id;
        }
        return FALLBACK_ADMIN_ID;
    }

    private void notifyListeners(long userId, WatchSettings snap) {
        for (ChangeListener l : listeners) {
            try {
                l.onChanged(userId, snap);
            } catch (Exception e) {
                log.warn("[watch] 配置变更回调失败: {}", e.getMessage());
            }
        }
    }

    /** 按 yml 默认值构造一份配置 */
    private WatchSettings defaultsFromProps() {
        WatchSettings s = new WatchSettings();
        s.setEnabled(props.isEnabled());
        s.setIntervalSeconds(props.getIntervalSeconds());
        s.setRules(defaultRulesFromProps());
        WatchSettings.DingTalkCfg dt = new WatchSettings.DingTalkCfg();
        dt.setWebhook(props.getDingtalk().getWebhook() == null ? "" : props.getDingtalk().getWebhook());
        s.setDingtalk(dt);
        s.setSurge(new WatchSettings.SurgeCfg(props.getSurge().getAmountWan(), props.getSurge().getTimes()));
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

    /** 深拷贝一份，外部改动不影响内存 */
    private WatchSettings copy(WatchSettings s) {
        WatchSettings copy = new WatchSettings();
        copy.setEnabled(s.isEnabled());
        copy.setIntervalSeconds(s.getIntervalSeconds());
        List<WatchSettings.Rule> rules = new ArrayList<>(s.getRules().size());
        for (WatchSettings.Rule r : s.getRules()) {
            rules.add(new WatchSettings.Rule(r.getIntervals(), r.getPercent()));
        }
        copy.setRules(rules);
        copy.setDingtalk(copyDingtalk(s.getDingtalk()));
        copy.setSurge(copySurge(s.getSurge()));
        List<WatchSettings.StockRef> refs = new ArrayList<>(s.getStocks().size());
        for (WatchSettings.StockRef r : s.getStocks()) {
            refs.add(new WatchSettings.StockRef(r.getCode(), r.getName()));
        }
        copy.setStocks(refs);
        return copy;
    }

    /** 某份配置里的盯盘标的转成轮询用的目标列表（名称缺失时按名录补） */
    private List<WatchTarget> toTargets(WatchSettings s) {
        List<WatchTarget> list = new ArrayList<>();
        for (WatchSettings.StockRef r : s.getStocks()) {
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

    /** 把超管配置里的钉钉字段回写到 props，DingTalkNotifier.send 读的是 props.getDingtalk() */
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

    /** 放量阈值拷贝；null 时退回 yml 默认值，保证调用方永远拿到可用对象 */
    private WatchSettings.SurgeCfg copySurge(WatchSettings.SurgeCfg src) {
        if (src == null) {
            return new WatchSettings.SurgeCfg(props.getSurge().getAmountWan(), props.getSurge().getTimes());
        }
        return new WatchSettings.SurgeCfg(src.getAmountWan(), src.getTimes());
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** 首次启动时把 watch-list.txt 里的名单搬进超管的配置，避免升级后要重新填一遍 */
    private void migrateLegacyList(WatchSettings target) {
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
        target.setStocks(refs);
        log.info("[watch] 已从 {} 迁移 {} 只标的到系统默认配置", props.getWatchListFile(), refs.size());
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

        normalizeSurge(s);

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

    /**
     * 放量阈值：越界、非法（含 null）就退回 yml 默认值。
     *
     * <p>不夹到边界值而是整体退回默认——夹到 1 万元会让"几乎每轮都算放量"，
     * 反而制造一堆告警，退回默认更安全。
     */
    private void normalizeSurge(WatchSettings s) {
        WatchSettings.SurgeCfg sg = s.getSurge();
        if (sg == null) {
            s.setSurge(new WatchSettings.SurgeCfg(props.getSurge().getAmountWan(), props.getSurge().getTimes()));
            return;
        }
        if (!(sg.getAmountWan() >= MIN_SURGE_AMOUNT_WAN && sg.getAmountWan() <= MAX_SURGE_AMOUNT_WAN)) {
            log.warn("[watch] 放量成交额阈值 {} 万元超出 {} ~ {} 万元，已退回默认 {} 万元",
                    sg.getAmountWan(), MIN_SURGE_AMOUNT_WAN, MAX_SURGE_AMOUNT_WAN, props.getSurge().getAmountWan());
            sg.setAmountWan(props.getSurge().getAmountWan());
        }
        if (!(sg.getTimes() >= MIN_SURGE_TIMES && sg.getTimes() <= MAX_SURGE_TIMES)) {
            log.warn("[watch] 放量倍数 {} 超出 {} ~ {}，已退回默认 {}",
                    sg.getTimes(), MIN_SURGE_TIMES, MAX_SURGE_TIMES, props.getSurge().getTimes());
            sg.setTimes(props.getSurge().getTimes());
        }
    }

    private String nameOf(String code) {
        StockNameIndex index = indexProvider.getIfAvailable();
        return index == null ? null : index.nameOf(code);
    }

    /**
     * 写盘；失败只记日志，不影响内存里已经生效的配置。
     *
     * <p>这里必须接「要写的内容」而不是调 {@link #snapshot(long)} 现取：
     * 首次为某个用户生成配置时，内容还没放进 {@link #byUser}，
     * snapshot 会走 {@link #get(long)} 再去载入一次 —— 载入里又要落盘，直接递归到栈溢出。
     */
    private void persist(long userId, WatchSettings s) {
        Path target = fileOf(userId);
        try {
            Path parent = target.getParent();
            if (parent != null && !Files.isDirectory(parent)) {
                Files.createDirectories(parent);
            }
            Files.write(target, writer.writeValueAsString(copy(s)).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("[watch] 盯盘配置落盘失败 {}：{}（内存里的配置仍然生效）", target, e.getMessage());
        }
    }

    /** 该用户的落盘文件（固定位置，父目录按需创建） */
    private Path fileOf(long userId) {
        return dataDir.file(FILE_PREFIX + userId + FILE_SUFFIX);
    }

    /** 已存在的配置文件：本人 →（仅超管）旧版 watch-config.json 的三个历史位置 */
    private Path resolveExisting(long userId) {
        Path own = fileOf(userId);
        if (Files.isRegularFile(own)) {
            return own;
        }
        if (userId != defaultUserId()) {
            return null;
        }
        Path legacyInData = dataDir.file(LEGACY_FILE_NAME);
        if (Files.isRegularFile(legacyInData)) {
            log.info("[watch] 发现旧版落盘配置 {}，将迁为超管的配置", legacyInData);
            return legacyInData;
        }
        Path local = Paths.get(System.getProperty("user.dir", "."), LEGACY_FILE_NAME);
        if (Files.isRegularFile(local)) {
            return local;
        }
        Path configured = configuredPath();
        if (configured != null && Files.isRegularFile(configured)) {
            return configured;
        }
        return null;
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
