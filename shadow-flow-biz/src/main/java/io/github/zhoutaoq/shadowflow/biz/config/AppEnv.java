package io.github.zhoutaoq.shadowflow.biz.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * app.env 的运行期装载器 —— <b>密钥的来源是机器，不是构建产物</b>。
 *
 * <p><b>为什么改成这样（v1.7.3）</b>：v1.6.4 起 app.env 被打进 jar 从 classpath 读，
 * 结果是「谁打包谁决定凭据」—— GitHub Actions 用仓库 Secret 打一份、本地用本机文件打一份，
 * 两边互相覆盖，线上凭据随发布方式漂移（2026-10-20 GitHub 登录开着开着就变回未开通，
 * 根因就是这个）。现在 <b>jar 里不再包含 app.env</b>（见 shadow-flow-biz/pom.xml 的
 * resource exclude），凭据只认部署机器上的那一份。
 *
 * <p><b>取值优先级</b>（高 → 低）：
 * <ol>
 *   <li>{@code -Dkey=value}：命令行 / 系统属性，最高，便于临时排障；</li>
 *   <li>进程环境变量：由 {@code remote.sh} 启动前从部署目录的 app.env 导出，运维可直接覆盖；</li>
 *   <li>外部文件 app.env：依次找 {@code -Dapp.env.file}、环境变量 {@code APP_ENV_FILE}、
 *       当前工作目录的 {@code ./app.env}（服务器上就是部署目录那份）；</li>
 *   <li>classpath:app.env：只作本地开发兜底，正常发行的包里已经没有它。</li>
 * </ol>
 * 高优先级已存在的键不再被低优先级覆盖。
 *
 * <p><b>文件格式</b>：沿用历史格式 {@code set "K=V"}（裸 {@code K=V} 也认），
 * {@code REM} / {@code #} / {@code @echo off} / 空行是注释或忽略。容忍 CRLF 与 BOM、行尾空白。
 *
 * <p><b>占位符保护</b>：值仍是模板占位文字（{@code 在此填写…} 开头）或为空时<b>不设置</b>，
 * 保持「未配置」的原语义 —— 否则模板一打包，超管口令就成了「在此填写超管初始口令」这句废话。
 *
 * <p><b>一处都没有就报错</b>：外部文件与 classpath 都读不到任何有效键时直接
 * {@link IllegalStateException} 终止启动，拒绝「密钥全空、服务看起来正常但什么都推不出去」
 * 的静默坏状态。
 *
 * <p>本类只认 app.env 这一个文件；server.env（部署目录 / JVM 参数）仍由
 * remote.sh 解析，与密钥无关，不归这里管。
 */
public final class AppEnv {

    /** 文件名（外部文件与 classpath 兜底同名） */
    public static final String RESOURCE = "app.env";

    /** 模板里未填写的占位值前缀：见到它就当这项没配 */
    static final String PLACEHOLDER_PREFIX = "在此填写";

    /** 约定的密钥键名：仅用于「一份文件都没有、全靠环境变量」时判断是否已配置 */
    private static final String[] KNOWN_KEYS = {
            "DB_PASSWORD", "ADMIN_PASSWORD", "SHADOWFLOW_API_TOKEN",
            "DINGTALK_WEBHOOK", "GITHUB_CLIENT_ID", "GITHUB_CLIENT_SECRET",
            "MAIL_USERNAME", "MAIL_PASSWORD"
    };

    private AppEnv() {
    }

    /**
     * 按优先级装载配置，把值设成系统属性（已被更高优先级占用的键跳过）。
     *
     * <p>返回实际设置的键名清单（<b>只有键，绝不含值</b> —— 调用方写日志时别把值带出去）。
     *
     * @throws IllegalStateException 外部文件与 classpath 都拿不到有效键值
     */
    public static List<String> load() {
        // 「文件里配了哪些键」——注意它统计的是**有效行**，不是「被本类设成系统属性的键」。
        // 服务器上 remote.sh 已经把 app.env 导成了环境变量，本类对每一项都会让路跳过；
        // 若把「跳过」当成「没配」，就会误判成零配置直接拒绝启动（v1.7.3 首次发布踩过）。
        List<String> configured = new ArrayList<>();

        Path external = locateExternal();
        if (external != null) {
            int n = readInto(external, configured);
            System.out.println("[app.env] 已从外部文件装载 " + n + " 项：" + external.toAbsolutePath());
        }

        // classpath 只补外部文件没有的键：正常发行包里已不含 app.env，
        // 这一层是给「本地 IDE 直接跑」和「还没换掉的老包」留的兼容通道。
        int fromClasspath = readClasspath(configured);
        if (fromClasspath > 0) {
            System.out.println("[app.env] 另外从 classpath 补了 " + fromClasspath + " 项（兜底）");
        }

        if (configured.isEmpty() && keysFromEnvironment().isEmpty()) {
            throw new IllegalStateException(
                    "没有拿到任何有效配置。密钥应当放在部署机器的 app.env 里，不随 jar 走：\n"
                            + "  1) 把 src/main/resources/app.env.example 复制成部署目录下的 app.env"
                            + "（如 /home/stock-backtesting/app.env）并填入真实值；\n"
                            + "  2) chmod 600 该文件；\n"
                            + "  3) 用 sh/deploy.bat deploy 发布，remote.sh 会在启动前把它导出成环境变量。\n"
                            + "也可以用 -Dapp.env.file=/path/to/app.env、环境变量 APP_ENV_FILE 指定位置，"
                            + "或直接把密钥导出成进程环境变量。");
        }
        List<String> envOnly = keysFromEnvironment();
        if (configured.isEmpty()) {
            System.out.println("[app.env] 未找到 app.env，改用进程环境变量：" + envOnly);
        } else {
            System.out.println("[app.env] 生效配置项：" + configured
                    + (envOnly.isEmpty() ? "" : "（另有环境变量：" + envOnly + "）"));
        }
        return configured;
    }

    /**
     * 直接从进程环境变量里就能拿到的约定键名。
     *
     * <p>用途只有一个：判断「没有任何 app.env 文件时，是否其实已经由环境变量配好了」。
     * 不做全量枚举 —— 环境里混着 PATH / JAVA_HOME 这类成千上万无关变量，认不过来也没必要。
     */
    private static List<String> keysFromEnvironment() {
        List<String> hit = new ArrayList<>();
        for (String key : KNOWN_KEYS) {
            if (isMeaningful(System.getenv(key))) {
                hit.add(key);
            }
        }
        return hit;
    }

    /**
     * 环境变量里的值算不算「真的配了」。
     *
     * <p>占位符也算没配 —— 启动脚本若把 app.env 原样导成环境变量，
     * {@code DB_PASSWORD} 就会是「在此填写数据库口令」这句废话，Spring 拿到它跟拿到空串一样没用，
     * 但会让「是否已配置」的判断全部失真。
     */
    private static boolean isMeaningful(String v) {
        if (v == null) {
            return false;
        }
        String t = v.trim();
        return !t.isEmpty() && !t.startsWith(PLACEHOLDER_PREFIX);
    }

    /**
     * 找机器上的那份 app.env。
     *
     * <p>顺序：{@code -Dapp.env.file} → 环境变量 {@code APP_ENV_FILE} → 工作目录 {@code ./app.env}。
     * 都没有返回 null（交给 classpath 兜底）。
     */
    private static Path locateExternal() {
        String prop = System.getProperty("app.env.file");
        if (prop != null && !prop.trim().isEmpty()) {
            Path p = Paths.get(prop.trim());
            if (Files.isReadable(p)) {
                return p;
            }
            System.out.println("[app.env] -Dapp.env.file 指向的文件不可读：" + p.toAbsolutePath());
        }
        String env = System.getenv("APP_ENV_FILE");
        if (env != null && !env.trim().isEmpty()) {
            Path p = Paths.get(env.trim());
            if (Files.isReadable(p)) {
                return p;
            }
            System.out.println("[app.env] APP_ENV_FILE 指向的文件不可读：" + p.toAbsolutePath());
        }
        Path cwd = Paths.get(RESOURCE);
        return Files.isReadable(cwd) ? cwd : null;
    }

    /** 读一个外部文件；返回文件里出现的有效键数 */
    private static int readInto(Path file, List<String> keys) {
        int n = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                n += apply(parse(line), keys);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + file.toAbsolutePath() + " 失败：" + e.getMessage(), e);
        }
        return n;
    }

    /** 读 classpath 里的 app.env（可能不存在）；返回有效键数 */
    private static int readClasspath(List<String> keys) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AppEnv.class.getClassLoader();
        }
        InputStream in = cl.getResourceAsStream(RESOURCE);
        if (in == null) {
            return 0;
        }
        int n = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                n += apply(parse(line), keys);
            }
        } catch (IOException e) {
            System.out.println("[app.env] 读 classpath:app.env 失败，忽略：" + e.getMessage());
        }
        return n;
    }

    /**
     * 登记一个有效键，并在没有被更高优先级占用时落到系统属性上。
     *
     * <p>「更高优先级」= 已有同名系统属性（{@code -D}）或已有同名环境变量（remote.sh 导出 / 运维手动设）。
     * 环境变量也要让路检查：否则文件里的旧值会被设成系统属性，
     * 而 Spring 解析占位符时系统属性优先于环境变量，表现为「改了环境变量却不生效」。
     *
     * @return 1 表示这是一个有效键（无论最终是否由本类落实），0 表示该行不是有效配置
     */
    private static int apply(String[] kv, List<String> keys) {
        if (kv == null) {
            return 0;
        }
        String key = kv[0];
        if (!keys.contains(key)) {
            keys.add(key);
        }
        if (System.getProperty(key) != null) {
            return 1;
        }
        String fromEnv = System.getenv(key);
        if (isMeaningful(fromEnv)) {
            return 1;
        }
        System.setProperty(key, kv[1]);
        return 1;
    }

    /**
     * 解析一行；不属于「有效键值」返回 null。
     *
     * <p>三种形态都认：{@code set "K=V"}（历史格式）、{@code K=V}（裸键值）、
     * 注释与空行（REM / # / @echo off / 空白）。
     * 值仍是模板占位文字（{@link #PLACEHOLDER_PREFIX} 开头）的行视为未填写，返回 null。
     */
    static String[] parse(String raw) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim().replace("\uFEFF", ""); // 容忍 BOM
        if (line.isEmpty() || line.startsWith("REM ") || line.startsWith("#")
                || line.equalsIgnoreCase("@echo off")) {
            return null;
        }
        if (line.startsWith("set ")) {
            line = line.substring(4).trim();
        }
        if (line.length() >= 2 && line.charAt(0) == '"' && line.charAt(line.length() - 1) == '"') {
            line = line.substring(1, line.length() - 1);
        }
        int eq = line.indexOf('=');
        if (eq <= 0) {
            return null;
        }
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        if (key.isEmpty() || value.isEmpty() || value.startsWith(PLACEHOLDER_PREFIX)) {
            return null;
        }
        return new String[]{key, value};
    }
}
