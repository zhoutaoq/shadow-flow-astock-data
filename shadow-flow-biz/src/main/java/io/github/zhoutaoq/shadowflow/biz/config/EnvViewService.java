package io.github.zhoutaoq.shadowflow.biz.config;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务环境变量总览（v1.6.1）—— 给「系统配置 → 环境变量查看」提供一份<b>只读</b>清单。
 *
 * <p><b>为什么要有这一页</b>：密钥不再写进 application.yml —— v1.6.4 起由
 * classpath 的 {@code app.env}（src/main/resources 下，不入库）在启动时经
 * {@link AppEnv} 装成<b>系统属性</b>（此前版本经进程环境变量），
 * yml 里只留 {@code ${DB_PASSWORD:}} 这类占位符。这样仓库里没有明文口令，
 * 但「线上到底装没装进来」也变得看不见 —— 本类就是把这层补上，
 * 免得排查时只能登服务器。
 *
 * <p>清单分两组：
 * <ul>
 *   <li>{@link #SECRETS} —— 凭据类（来自 app.env 或系统配置页），值<b>一律脱敏</b>，
 *       只露前后各 4 位；</li>
 *   <li>{@link #RUNTIMES} —— 运行参数（来自 server.env），无密钥，明文展示，
 *       用来核对「这次发布实际带上了哪些启动参数」。</li>
 * </ul>
 *
 * <p><b>不做成「把进程环境整个吐出来」</b>：那会把服务器上其它服务的变量
 * （PATH / JAVA_HOME / 别家的口令）一并带到浏览器眼前。这里只列本服务自己约定的那几个键，
 * 多一个都不给 —— 想看全量就登服务器。
 *
 * <p><b>取值</b>：直接读 {@link System#getenv(String)}。令牌与机器人另外认「系统配置页」
 * 那一层覆盖（配置行优先于环境变量，与 {@link IntegrationConfigService} 的口径一致），
 * 命中的项来源标成「系统配置页」，值取配置行那一份。
 */
@Service
public class EnvViewService {

    /**
     * 凭据类变量 —— 值脱敏。
     *
     * <p>{@code name} 就是 app.env 里的键（历史上也是环境变量名），页面直接照这个核对；
     * 顺序与 app.env 保持一致，方便左右对照。
     */
    private static final List<Field> SECRETS = Collections.unmodifiableList(Arrays.asList(
            new Field("DB_PASSWORD", "数据库口令",
                    "仅数据库模式（shadowflow.db.enabled=true）连 MySQL 时使用；无数据库模式不参与连接。"),
            new Field("ADMIN_PASSWORD", "超管初始口令",
                    "只在 admin 账号还不存在时用于初始化，之后一律以页面改过的为准。"),
            new Field("SHADOWFLOW_API_TOKEN", "行情数据服务令牌",
                    "数据更新的核心凭据。优先取超管在「第三方对接」页维护的配置行，"
                            + "这里是配置行没填时的出厂兜底。"),
            new Field("DINGTALK_WEBHOOK", "系统通知机器人 Webhook",
                    "平台自己发通知的出口（发布通知 / 注册通知 / 用户反馈），兼作盯盘的默认群。"
                            + "同样配置行优先。")
    ));

    /**
     * 运行参数 —— 来自 server.env，由 remote.sh 解析后 export 给 java 进程，无密钥、明文展示。
     *
     * <p>这几个值不参与业务逻辑，但「ARGS 里少一个开关就会让对应功能静默失效」，
     * 摆在这里可以一眼核对本次发布到底带上了什么。
     */
    private static final List<Field> RUNTIMES = Collections.unmodifiableList(Arrays.asList(
            new Field("REMOTE_DIR", "部署目录", "jar、日志、remote.sh 与两个 env 文件所在目录。"),
            new Field("JAR_NAME", "jar 基名", "正式包 = <JAR_NAME>.jar，发布时先落成 .jar.new 再改名。"),
            new Field("JAVA", "远端 JDK", "远端启动用的 java 可执行文件绝对路径。"),
            new Field("OPTS", "JVM 参数", "内存与 GC 参数。"),
            new Field("ARGS", "启动参数", "端口与各功能开关；少一个开关，对应功能会在服务照常运行的情况下静默失效。"),
            new Field("LOG", "日志文件", "相对部署目录的日志文件名。")
    ));

    /**
     * 环境变量名 → 系统配置页的表单字段名。
     *
     * <p>只有这两项存在「配置页覆盖」这一层（见 {@link IntegrationConfigService} 里登记的对接项）；
     * 数据库口令与超管口令没有配置页入口，只能来自环境变量。
     */
    private static final Map<String, String> PAGE_FIELD = new LinkedHashMap<>();

    static {
        PAGE_FIELD.put("SHADOWFLOW_API_TOKEN", "dataApiToken");
        PAGE_FIELD.put("DINGTALK_WEBHOOK", "dingtalkWebhook");
    }

    private final IntegrationConfigService integration;

    public EnvViewService(IntegrationConfigService integration) {
        this.integration = integration;
    }

    /** 页面上的一份总览：凭据（脱敏）+ 运行参数（明文） */
    public Map<String, Object> view() {
        Map<String, String> pageSource = new LinkedHashMap<>();
        Map<String, String> pageDisplay = new LinkedHashMap<>();
        collectPageOverrides(pageSource, pageDisplay);

        List<Map<String, Object>> secrets = new ArrayList<>(SECRETS.size());
        for (Field f : SECRETS) {
            secrets.add(secretItem(f, pageSource, pageDisplay));
        }
        List<Map<String, Object>> runtimes = new ArrayList<>(RUNTIMES.size());
        for (Field f : RUNTIMES) {
            runtimes.add(runtimeItem(f));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("secrets", secrets);
        out.put("runtimes", runtimes);
        return out;
    }

    /* ---------------- 内部实现 ---------------- */

    /**
     * 把「系统配置页」那一层的来源与回显值摘出来。
     *
     * <p>复用 {@link IntegrationConfigService#view()} 而不是自己再查一遍配置表：
     * 那边已经把「配置行 / yml 兜底 / 都没配」判好了，两处各判一次迟早会走偏。
     */
    private void collectPageOverrides(Map<String, String> source, Map<String, String> display) {
        if (integration == null) {
            return;
        }
        Object items = integration.view().get("items");
        if (!(items instanceof List)) {
            return;
        }
        for (Object o : (List<?>) items) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<?, ?> m = (Map<?, ?>) o;
            String name = str(m.get("name"));
            if (name.isEmpty()) {
                continue;
            }
            source.put(name, str(m.get("source")));
            display.put(name, str(m.get("display")));
        }
    }

    /** 凭据项：配置页覆盖过就用配置页那份，否则看环境变量 */
    private Map<String, Object> secretItem(Field f, Map<String, String> pageSource, Map<String, String> pageDisplay) {
        String pageField = PAGE_FIELD.get(f.name);
        Map<String, Object> one = base(f);
        one.put("secret", true);

        if (pageField != null && "config".equals(pageSource.get(pageField))) {
            one.put("source", "系统配置页");
            one.put("display", str(pageDisplay.get(pageField)));
            one.put("configured", true);
            return one;
        }
        // v1.6.4 起密钥由 AppEnv 装成系统属性；兼容旧部署仍认进程环境变量
        String v = nz(System.getProperty(f.name));
        String source = v.isEmpty() ? null : "app.env";
        if (v.isEmpty()) {
            v = nz(System.getenv(f.name));
            source = v.isEmpty() ? "未设置" : "环境变量";
        }
        one.put("source", source);
        one.put("display", IntegrationConfigService.mask(v));
        one.put("configured", !v.isEmpty());
        return one;
    }

    /** 运行参数项：无密钥，明文给 */
    private Map<String, Object> runtimeItem(Field f) {
        String v = nz(System.getenv(f.name));
        Map<String, Object> one = base(f);
        one.put("secret", false);
        one.put("value", v);
        one.put("configured", !v.isEmpty());
        return one;
    }

    private static Map<String, Object> base(Field f) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put("name", f.name);
        one.put("label", f.label);
        one.put("hint", f.hint);
        return one;
    }

    private static String nz(String s) {
        return s == null ? "" : s.trim();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 一个环境变量项的定义（只读展示，没有可写字段） */
    private static final class Field {
        private final String name;
        private final String label;
        private final String hint;

        private Field(String name, String label, String hint) {
            this.name = name;
            this.label = label;
            this.hint = hint;
        }
    }
}
