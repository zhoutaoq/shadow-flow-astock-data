package io.github.zhoutaoq.shadowflow.biz.config;

import io.github.zhoutaoq.shadowflow.biz.auth.RegistrationNotifyProperties;
import io.github.zhoutaoq.shadowflow.biz.feedback.FeedbackProperties;
import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;
import io.github.zhoutaoq.shadowflow.biz.repo.SysConfigRepository;
import io.github.zhoutaoq.shadowflow.biz.support.TokenProvider;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 第三方对接配置的统一出口（v1.5.9）。
 *
 * <p><b>为什么要有这一个类</b>：改造前平台自己的凭据散在两处、归属还不对 ——
 * 行情数据服务令牌挂在每个用户名下（个人一份，谁没配谁取不到数）、
 * 反馈与注册的关键词又埋在 application.yml（换个词要登服务器改文件重启）。
 *
 * <p>现在的口径：<b>平台自己对接第三方的凭据归系统，只存一份、只改一处、全站生效</b>。
 * 全部落在系统级配置行（{@code ownerId = 0}），与具体用户无关：
 * <ul>
 *   <li>{@code shadowflow / token} —— 行情数据接口令牌；</li>
 *   <li>{@code dingtalk / webhook} —— <b>系统通知机器人</b>：平台自己发通知的出口
 *       （发布通知 / 注册通知 / 反馈兜底），兼作盯盘的默认群；</li>
 *   <li>{@code feedback / webhook} —— 反馈专用机器人，留空即复用上面那只；</li>
 *   <li>{@code feedback / keyword}、{@code auth-notify / keyword} —— 「自定义关键词」模式必须命中的词。</li>
 * </ul>
 *
 * <p><b>但别把用户的东西也收进来</b>：盯盘告警是各人发各人的群，属于用户级 ——
 * 机器人配在盯盘页、存在各人的 {@code watch-{userId}.json} 里，用户没自配才回落到
 * 上面的系统通知机器人（见 {@link io.github.zhoutaoq.shadowflow.biz.watch.WatchConfigStore#webhookOf(long)}）。
 * 把这一层也收归系统级等于让所有用户的告警挤进同一个群，那不是 SaaS 化，是把多租户砍成单租户。
 *
 * <p><b>取值规则</b>：配置行优先，行里没有（或为空）才回落到 application.yml 里的出厂值。
 * 这样新装环境不配也能跑（yml 里有种子值），而改配置不必再碰文件。
 *
 * <p><b>进程内的传播</b>：本类的取值是「每次调用现算」（配置行 → yml 兜底），
 * 而通知目标由 {@link NotifyWiring} 的解析器在<b>每次发送时</b>调 {@link #dingtalkWebhook()}，
 * 所以「保存完立刻生效」不需要任何回写或缓存失效。
 *
 * <p>本类同时充当 {@link TokenProvider}：行情客户端只要一个「当前令牌」，
 * 不必知道它存在配置表还是 yml 里。
 */
@Service
public class IntegrationConfigService implements TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(IntegrationConfigService.class);

    /** 场景：行情数据服务凭据（v1.6.0 由旧值改名，旧行仍认，见 SCENE_DATA_API_LEGACY） */
    private static final String SCENE_DATA_API = "shadowflow";
    /** 场景：钉钉机器人 */
    private static final String SCENE_DINGTALK = "dingtalk";
    /** 场景：用户反馈 */
    private static final String SCENE_FEEDBACK = "feedback";
    /** 场景：注册通知 */
    private static final String SCENE_AUTH_NOTIFY = "auth-notify";

    private static final String KEY_TOKEN = "token";
    private static final String KEY_WEBHOOK = "webhook";
    private static final String KEY_KEYWORD = "keyword";

    /**
     * 页面上要维护的全部对接项。
     *
     * <p>{@code name} 是页面的表单字段名，也是保存请求里的 key；
     * {@code secret} 决定回显时是否打码 —— 令牌与 webhook 都自带凭据，
     * 明文回显等于把它贴到任何一个打开配置页的人眼前。
     */
    private static final List<Field> FIELDS = Collections.unmodifiableList(Arrays.asList(
            new Field("dataApiToken", SCENE_DATA_API, KEY_TOKEN, "数据接口令牌", true,
                    "行情数据服务的访问凭据，页面只回显前后各 4 位。"),
            new Field("dingtalkWebhook", SCENE_DINGTALK, KEY_WEBHOOK, "系统通知机器人", true,
                    "平台自己发通知的出口：发布通知、注册通知、用户反馈兜底。"
                            + "另外也兼作盯盘的默认群 —— 用户没在盯盘页配自己的机器人时，他的告警发到这里。"
                            + "用户配了自己的机器人就只进他自己的群，不受这里影响。"),
            new Field("feedbackWebhook", SCENE_FEEDBACK, KEY_WEBHOOK, "反馈专用 Webhook", true,
                    "留空表示复用上面的系统通知机器人 —— 反馈与平台通知进同一个群，不用重复配一只。"),
            new Field("feedbackKeyword", SCENE_FEEDBACK, KEY_KEYWORD, "反馈消息关键词", false,
                    "钉钉「自定义关键词」模式下必须命中的词，消息首行会拼成 [关键词] 用户反馈。"),
            new Field("registerKeyword", SCENE_AUTH_NOTIFY, KEY_KEYWORD, "注册通知关键词", false,
                    "同上，用于新用户注册通知；复用同一只机器人时保持与其他消息一致。")
    ));

    private final SysConfigRepository repo;
    private final MarketDataProperties dataProps;
    private final WatchProperties watchProps;
    private final FeedbackProperties feedbackProps;
    private final RegistrationNotifyProperties notifyProps;

    public IntegrationConfigService(SysConfigRepository repo,
                                    MarketDataProperties dataProps,
                                    WatchProperties watchProps,
                                    FeedbackProperties feedbackProps,
                                    RegistrationNotifyProperties notifyProps) {
        this.repo = repo;
        this.dataProps = dataProps;
        this.watchProps = watchProps;
        this.feedbackProps = feedbackProps;
        this.notifyProps = notifyProps;
    }

    @PostConstruct
    public void init() {
        refresh();
        log.info("[integration] 对接配置已载入：令牌={}，钉钉机器人={}，反馈机器人={}（来源：配置表 → yml 兜底）",
                mask(token()), mask(dingtalkWebhook()),
                mask(feedbackWebhook().isEmpty() ? dingtalkWebhook() : feedbackWebhook()));
    }

    /* ---------------- 运行期取值 ---------------- */

    /** 当前生效的 行情数据服务令牌；未配置时回落到 yml 的种子值 */
    @Override
    public String token() {
        return effective("dataApiToken");
    }

    /** 当前生效的钉钉机器人 webhook；空串表示没配（推送只留日志） */
    public String dingtalkWebhook() {
        return effective("dingtalkWebhook");
    }

    /** 反馈要用的 webhook：没单独配就复用钉钉那只，两只都没有时返回空串 */
    public String feedbackWebhook() {
        String own = effective("feedbackWebhook");
        return own.isEmpty() ? dingtalkWebhook() : own;
    }

    /** 反馈消息的关键词前缀 */
    public String feedbackKeyword() {
        return effective("feedbackKeyword");
    }

    /** 注册通知的关键词前缀 */
    public String registerKeyword() {
        return effective("registerKeyword");
    }

    /**
     * 配置变更后的即时生效钩子。
     *
     * <p><b>v1.6.0 起本方法不再回写任何 bean 字段</b>：改造前必须把生效的 webhook 回写进
     * {@code watch.dingtalk.webhook}，因为通知器是启动时读一次、之后不再取；
     * 现在通知目标由 {@link NotifyWiring#notifyTargetResolver} 在<b>每次发送时现取</b>，
     * 所以「保存完立刻生效」是天然的，不需要回写这一层间接。
     *
     * <p>保留方法本身是为了让调用方（保存/恢复出厂）有一个统一的「变更已落库」出口，
     * 将来若要加缓存失效，加在这里。
     */
    public void refresh() {
        log.info("[integration] 对接配置变更已生效：钉钉机器人={}，反馈机器人={}",
                mask(dingtalkWebhook()),
                mask(feedbackWebhook().isEmpty() ? dingtalkWebhook() : feedbackWebhook()));
    }

    /* ---------------- 页面读写 ---------------- */

    /** 页面要的一份总览：每项的当前状态（值一律按需打码） */
    public Map<String, Object> view() {
        List<Map<String, Object>> items = new ArrayList<>(FIELDS.size());
        int overridden = 0;
        for (Field f : FIELDS) {
            String value = effective(f.name);
            boolean stored = stored(f) != null;
            if (stored) {
                overridden++;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("name", f.name);
            one.put("label", f.label);
            one.put("hint", f.hint);
            one.put("secret", f.secret);
            one.put("configured", !value.isEmpty());
            // 值从哪来：config=配置行覆盖过，yml=出厂默认，none=两处都没有
            one.put("source", stored ? "config" : (value.isEmpty() ? "none" : "yml"));
            one.put("display", f.secret ? mask(value) : value);
            items.add(one);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("overridden", overridden);
        out.put("total", FIELDS.size());
        // 反馈机器人留空时的实际落点，页面据此显示「当前复用哪只」
        out.put("feedbackFallbackToDingtalk", effective("feedbackWebhook").isEmpty());
        return out;
    }

    /**
     * 保存。
     *
     * <p>语义刻意做成「留空 = 这一项不动」而不是「留空 = 清空」：
     * 令牌与 webhook 在页面上是打码回显的，用户不动它就不会带上任何值，
     * 若把「没带值」当成「要清空」，一次普通保存就能把线上机器人配没了。
     * 真要清空走 {@code clear} 名单（页面上是每个凭据旁边的「清除」按钮），
     * 清空后该项即回落到 yml 出厂值，或整体 {@link #reset()} 回到出厂状态。
     *
     * @param values 字段名 → 新值；空值、缺项都表示不修改
     * @param clear  要清掉的字段名清单
     * @return 保存后的一份总览
     */
    public Map<String, Object> save(Map<String, String> values, List<String> clear) {
        if (repo == null) {
            throw new IllegalArgumentException("配置存储不可用，无法保存");
        }
        Set<String> toClear = clear == null ? Collections.<String>emptySet() : new HashSet<>(clear);
        int written = 0;
        int cleared = 0;
        for (Field f : FIELDS) {
            if (toClear.contains(f.name)) {
                if (remove(f)) {
                    cleared++;
                }
                continue;
            }
            String v = values == null ? null : values.get(f.name);
            if (!StringUtils.hasText(v)) {
                continue;
            }
            put(f, v.trim());
            written++;
        }
        refresh();
        log.info("[integration] 对接配置已保存：写入 {} 项，清除 {} 项（改完即生效，无需重启）", written, cleared);
        return view();
    }

    /** 全部回到 application.yml 的出厂值（删掉所有系统级对接配置行） */
    public Map<String, Object> reset() {
        if (repo == null) {
            throw new IllegalArgumentException("配置存储不可用，无法恢复");
        }
        int removed = 0;
        for (Field f : FIELDS) {
            if (remove(f)) {
                removed++;
            }
        }
        refresh();
        log.info("[integration] 对接配置已恢复出厂值：清除 {} 项，改回读 application.yml", removed);
        return view();
    }

    /* ---------------- 内部实现 ---------------- */

    /** 配置行优先，没有才用 yml 出厂值 */
    private String effective(String name) {
        Field f = fieldOf(name);
        if (f == null) {
            return "";
        }
        SysConfig stored = stored(f);
        if (stored != null && StringUtils.hasText(stored.getConfigValue())) {
            return stored.getConfigValue().trim();
        }
        return ymlDefault(name);
    }

    private String ymlDefault(String name) {
        switch (name) {
            case "dataApiToken":
                return dataProps == null || dataProps.getApi() == null
                        ? "" : nz(dataProps.getApi().getToken());
            case "dingtalkWebhook":
                return watchProps == null || watchProps.getDingtalk() == null
                        ? "" : nz(watchProps.getDingtalk().getWebhook());
            case "feedbackWebhook":
                return feedbackProps == null ? "" : nz(feedbackProps.getWebhook());
            case "feedbackKeyword":
                return feedbackProps == null ? "" : nz(feedbackProps.getKeyword());
            case "registerKeyword":
                return notifyProps == null ? "" : nz(notifyProps.getKeyword());
            default:
                return "";
        }
    }

    /**
     * 该项在系统级配置里的行；没配过返回 null。
     *
     * <p>v1.6.0 的场景值由 {@code waizao} 改为 {@code shadowflow}，这里对新旧两个场景值都认：
     * 新值优先，找不到再回退到 {@link SysConfigRepository#SCENE_DATA_API_LEGACY}。
     * 少了这一步，升级后既有的令牌行会被判成「没配过」，
     * 系统悄悄换用 yml 里的出厂值 —— 表现为行情突然取不到数，且日志上看不出原因。
     */
    private SysConfig stored(Field f) {
        if (repo == null) {
            return null;
        }
        SysConfig legacy = null;
        for (SysConfig c : repo.list(SysConfigRepository.SYSTEM_OWNER)) {
            if (!f.key.equals(c.getConfigKey())) {
                continue;
            }
            if (f.scene.equals(c.getConfigScene())) {
                return c;
            }
            if (SysConfigRepository.SCENE_DATA_API_LEGACY.equals(c.getConfigScene())) {
                legacy = c;
            }
        }
        return legacy;
    }

    private void put(Field f, String value) {
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SysConfigRepository.SYSTEM_OWNER);
        cfg.setConfigScene(f.scene);
        cfg.setConfigKey(f.key);
        cfg.setConfigValue(value);
        cfg.setRemark("系统配置页维护");
        repo.save(cfg);
    }

    /** 删掉该项的配置行；本来就没配过返回 false */
    private boolean remove(Field f) {
        SysConfig exist = stored(f);
        if (exist == null || exist.getId() == null) {
            return false;
        }
        repo.delete(SysConfigRepository.SYSTEM_OWNER, exist.getId());
        return true;
    }

    private static Field fieldOf(String name) {
        for (Field f : FIELDS) {
            if (f.name.equals(name)) {
                return f;
            }
        }
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    /** 打码：只露前后各 4 位，短值整体隐掉 */
    private static String mask(String v) {
        if (v == null || v.isEmpty()) {
            return "";
        }
        if (v.length() <= 8) {
            return "****";
        }
        return v.substring(0, 4) + "****" + v.substring(v.length() - 4);
    }

    /** 一个对接项的定义 */
    private static final class Field {
        private final String name;
        private final String scene;
        private final String key;
        private final String label;
        private final boolean secret;
        private final String hint;

        private Field(String name, String scene, String key, String label, boolean secret, String hint) {
            this.name = name;
            this.scene = scene;
            this.key = key;
            this.label = label;
            this.secret = secret;
            this.hint = hint;
        }
    }
}
