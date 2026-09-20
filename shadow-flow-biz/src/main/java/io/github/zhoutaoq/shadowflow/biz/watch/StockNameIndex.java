package io.github.zhoutaoq.shadowflow.biz.watch;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 股票名称 → 代码 的索引，数据来自 stock-list.json（与行情数据服务用同一份文件，但这里是独立解析，
 * 不复用其它包的代码）。
 *
 * <p>只收沪深A股（6 位数字 + 60/68/00/30 前缀）。文件改了会按修改时间自动重建，
 * 新增标的不用重启。
 */
public class StockNameIndex {

    private static final Logger log = LoggerFactory.getLogger(StockNameIndex.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final String configuredPath;

    private volatile Map<String, String> nameToCode = Collections.emptyMap();
    private volatile Map<String, String> codeToName = Collections.emptyMap();
    private volatile long loadedStamp = -1L;

    public StockNameIndex(String configuredPath) {
        this.configuredPath = configuredPath == null || configuredPath.trim().isEmpty()
                ? "resources/stock-list.json" : configuredPath.trim();
    }

    /** 按名称查代码；查不到返回 null */
    public String codeOf(String name) {
        refreshIfNeeded();
        if (name == null) {
            return null;
        }
        return nameToCode.get(name.trim());
    }

    /** 按代码查名称；用于名单里只写了代码时把名称补上 */
    public String nameOf(String code) {
        refreshIfNeeded();
        if (code == null) {
            return null;
        }
        return codeToName.get(code.trim());
    }

    private void refreshIfNeeded() {
        Path path = resolve();
        if (path == null) {
            if (loadedStamp != -2L) {
                log.warn("[watch] 未找到股票名录文件（{}），名称无法识别，请在盯盘名单里直接写 6 位代码",
                        configuredPath);
                loadedStamp = -2L;
            }
            return;
        }
        try {
            long stamp = Files.getLastModifiedTime(path).toMillis();
            if (stamp == loadedStamp) {
                return;
            }
            load(path);
            loadedStamp = stamp;
        } catch (Exception e) {
            log.warn("[watch] 读取股票名录失败 {}: {}", path, e.getMessage());
        }
    }

    private void load(Path path) throws Exception {
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        JsonNode root = mapper.readTree(json);
        if (root == null || !root.isArray()) {
            log.warn("[watch] 股票名录不是 JSON 数组: {}", path);
            return;
        }
        Map<String, String> byName = new HashMap<>();
        Map<String, String> byCode = new HashMap<>();
        for (JsonNode n : root) {
            String code = n.path("code").asText(null);
            String name = n.path("name").asText("");
            if (!isAShare(code)) {
                continue;
            }
            byCode.put(code, name.trim());
            if (!name.trim().isEmpty()) {
                byName.put(name.trim(), code);
            }
        }
        nameToCode = byName;
        codeToName = byCode;
        log.info("[watch] 股票名录载入 {} 条（用于名称识别）", byCode.size());
    }

    /** 沪深A股：6 位数字且 60 / 68 / 00 / 30 开头 */
    public static boolean isAShare(String code) {
        if (code == null || code.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (!Character.isDigit(code.charAt(i))) {
                return false;
            }
        }
        return code.startsWith("60") || code.startsWith("68")
                || code.startsWith("00") || code.startsWith("30");
    }

    /** 依次尝试：配置路径 → 工作目录相对 → 项目根相对 → src/main/resources → classpath */
    private Path resolve() {
        String p = configuredPath.replace("\\", "/");
        Path[] candidates = new Path[]{
                Paths.get(p),
                Paths.get(System.getProperty("user.dir"), p),
                parentOf(Paths.get(System.getProperty("user.dir"))) == null
                        ? null : parentOf(Paths.get(System.getProperty("user.dir"))).resolve(p),
                Paths.get(System.getProperty("user.dir"), "src", "main", "resources", p)
        };
        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                return c;
            }
        }
        for (String cp : new String[]{p, baseName(p)}) {
            try (InputStream in = getClass().getClassLoader().getResourceAsStream(cp)) {
                if (in == null) {
                    continue;
                }
                File f = new File(getClass().getClassLoader().getResource(cp).toURI());
                return f.toPath();
            } catch (Exception ignored) {
                // 继续尝试下一个
            }
        }
        return null;
    }

    private static Path parentOf(Path p) {
        return p == null ? null : p.getParent();
    }

    private static String baseName(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }
}
