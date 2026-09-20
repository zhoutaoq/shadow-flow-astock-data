package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 构建信息，供页面页脚展示「版本号 + 发布时间」，不需要登录。
 *
 * <p>值来自打包时变量替换生成的 {@code build-info.properties}（见 pom 的 {@code <resources>}），
 * 所以不要在 HTML 或 JS 里写死版本 —— 改 pom 的 version 即自动同步。
 *
 * <p>拿不到构建信息时退到 jar 清单的 {@code Implementation-Version}，再拿不到显示 dev，
 * 保证页脚在任何运行方式（IDE 直跑、未过滤的资源）下都不会空着或报错。
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

    private static final DateTimeFormatter DISPLAY =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Shanghai"));

    /** 进程内只算一次：构建信息在运行期不会变 */
    private static final String VERSION;
    private static final String BUILD_TIME;

    static {
        Properties p = new Properties();
        try (InputStream in = VersionController.class.getClassLoader()
                .getResourceAsStream("build-info.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (Exception ignored) {
            // 读不到就当没有这个文件，下面逐级兜底
        }

        String v = p.getProperty("build.version", "").trim();
        if (v.isEmpty() || v.contains("${")) {
            Package pkg = VersionController.class.getPackage();
            v = pkg == null ? null : pkg.getImplementationVersion();
        }
        if (v == null || v.isEmpty()) {
            v = "dev";
        }
        VERSION = v;
        BUILD_TIME = beijing(p.getProperty("build.time", "").trim());
    }

    /** 构建时间是 UTC（ISO-8601 尾部 Z），转成北京时间展示；格式意外就原样返回 */
    private static String beijing(String raw) {
        if (raw == null || raw.isEmpty() || raw.contains("${")) {
            return "";
        }
        try {
            return DISPLAY.format(Instant.parse(raw));
        } catch (Exception e) {
            return raw;
        }
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> version() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("version", VERSION);
        data.put("buildTime", BUILD_TIME);
        return ApiResponse.ok(data);
    }
}
