package io.github.zhoutaoq.shadowflow.biz.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * app.env 的运行期装载器（v1.6.4）—— 密钥从 classpath 读，不再走进程环境变量。
 *
 * <p><b>为什么要有这一个类</b>：v1.6.3 及之前，app.env 放在 {@code sh/} 目录，
 * 只能靠服务器上的 {@code remote.sh} 逐行解析后 export 进进程环境 ——
 * 本地直接 {@code java -jar} 或 IDE 起进程时完全没人解析它，
 * {@code application.yml} 里的 {@code ${DINGTALK_WEBHOOK:}} 占位符永远落空。
 * 现在文件挪进 {@code src/main/resources}，打进 jar 随包走，本类在
 * {@code main()} 最开头把它读出来、设成 <b>系统属性</b>（不是环境变量）：
 * Spring 解析 {@code ${XXX:}} 占位符时系统属性本来就优先于环境变量，
 * 所以 application.yml 一行都不用改。
 *
 * <p><b>文件格式</b>：沿用历史格式 {@code set "K=V"}（裸 {@code K=V} 也认），
 * {@code REM} / {@code #} / {@code @echo off} / 空行是注释或忽略。
 * 容忍 CRLF 与行尾空白。
 *
 * <p><b>占位符保护</b>：值还是模板占位文字（{@code 在此填写…} 开头）或为空时<b>不设置</b>，
 * 保持「未配置」的原语义 —— 否则模板一打包，超管口令就成了「在此填写超管初始口令」这句废话，
 * 行情令牌也拿垃圾值去打接口。
 *
 * <p><b>优先级</b>：同名系统属性已存在时不覆盖 —— {@code -D} 与命令行
 * {@code --watch.dingtalk.webhook=…} 等覆盖手段依旧有效（本地验证钉钉链路时
 * 指向假机器人的安全闸就靠这一层）。
 *
 * <p><b>读不到就报错</b>：classpath 里没有 {@code app.env}（新环境没从模板复制、
 * 或忘了随包发布）直接 {@link IllegalStateException} 终止启动，
 * 拒绝「密钥全空、服务看起来正常但什么都推不出去」的静默坏状态。
 *
 * <p>本类只认 app.env 这一个文件；server.env（部署目录 / JVM 参数）仍由
 * remote.sh 在服务器上注入进程环境，与密钥无关，不归这里管。
 */
public final class AppEnv {

    /** classpath 里的文件名（与 src/main/resources 下保持同名） */
    public static final String RESOURCE = "app.env";

    /** 模板里未填写的占位值前缀：见到它就当这项没配 */
    static final String PLACEHOLDER_PREFIX = "在此填写";

    private AppEnv() {
    }

    /**
     * 读 classpath:app.env 并把其中的键设成系统属性。
     *
     * <p>返回实际设置的键名清单（<b>只有键，绝不含值</b>——调用方写日志时别把值带出去）。
     *
     * @throws IllegalStateException 文件不存在或一行有效键值都解析不出来
     */
    public static List<String> load() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = AppEnv.class.getClassLoader();
        }
        InputStream in = cl.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IllegalStateException(
                    "classpath 里找不到 " + RESOURCE + "：请把 src/main/resources/app.env.example"
                            + " 复制成同目录的 app.env 并填入真实值，再重新打包（该文件不入库）。");
        }
        List<String> keys = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] kv = parse(line);
                if (kv == null) {
                    continue;
                }
                String key = kv[0];
                String value = kv[1];
                if (System.getProperty(key) != null) {
                    continue; // 已有 -D 或命令行设置，让路
                }
                System.setProperty(key, value);
                keys.add(key);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 " + RESOURCE + " 失败：" + e.getMessage(), e);
        }
        if (keys.isEmpty()) {
            throw new IllegalStateException(
                    RESOURCE + " 里没有任何有效配置（全是注释或未填写的占位值）："
                            + "请把 src/main/resources/app.env.example 复制成同目录的 app.env 并填入真实值。");
        }
        System.out.println("[app.env] 已从 classpath 装载：" + keys);
        return keys;
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
