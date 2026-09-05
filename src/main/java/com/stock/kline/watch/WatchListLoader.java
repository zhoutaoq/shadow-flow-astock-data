package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 读取盯盘名单：每行一个股票名称，自动按名称识别代码。
 *
 * <p>每行支持三种写法，可混用：
 * <pre>
 * 平安银行              # 按名称查 stock-list.json
 * 600519                # 直接写 6 位代码
 * 600519 贵州茅台       # 代码 + 名称（空格 / 逗号 / 顿号都认）
 * </pre>
 * <code>#</code> 开头是注释，空行忽略。每轮调度都会重读，改文件不用重启。
 */
public class WatchListLoader {

    private static final Logger log = LoggerFactory.getLogger(WatchListLoader.class);

    private final String configuredPath;
    private final StockNameIndex index;

    public WatchListLoader(String configuredPath, StockNameIndex index) {
        this.configuredPath = configuredPath == null || configuredPath.trim().isEmpty()
                ? "resources/watch-list.txt" : configuredPath.trim();
        this.index = index;
    }

    /** 读一版名单；文件不存在或全为空时返回空列表 */
    public List<WatchTarget> load() {
        Path path = resolve();
        if (path == null) {
            log.warn("[watch] 盯盘名单文件不存在（{}），本轮跳过", configuredPath);
            return new ArrayList<>();
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[watch] 读取盯盘名单失败 {}: {}", path, e.getMessage());
            return new ArrayList<>();
        }

        List<WatchTarget> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String raw : lines) {
            String line = strip(raw);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            WatchTarget t = parseLine(line);
            if (t == null) {
                log.warn("[watch] 盯盘名单这一行识别不出代码，已跳过：{}", line);
                continue;
            }
            if (seen.add(t.getCode())) {
                targets.add(t);
            }
        }
        return targets;
    }

    /** 一行 → 标的；识别不出返回 null */
    private WatchTarget parseLine(String line) {
        // 分隔符：空格 / 制表 / 逗号（含中文）/ 顿号
        String[] parts = line.split("[\\s,，、]+");
        String first = parts.length > 0 ? parts[0] : "";
        String rest = parts.length > 1 ? parts[1] : "";

        if (StockNameIndex.isAShare(first)) {
            String name = rest.isEmpty() ? index.nameOf(first) : rest;
            return new WatchTarget(name == null || name.isEmpty() ? first : name, first);
        }
        // 名称在前、代码在后（如「贵州茅台 600519」）
        for (String p : parts) {
            if (StockNameIndex.isAShare(p)) {
                return new WatchTarget(first, p);
            }
        }
        String code = index.codeOf(line);
        if (code == null) {
            return null;
        }
        return new WatchTarget(line, code);
    }

    /** 去掉 BOM、行内注释与首尾空白 */
    private static String strip(String raw) {
        String s = raw == null ? "" : raw;
        if (s.startsWith("\uFEFF")) {
            s = s.substring(1);
        }
        int i = s.indexOf('#');
        if (i >= 0) {
            s = s.substring(0, i);
        }
        return s.trim();
    }

    private Path resolve() {
        String p = configuredPath.replace("\\", "/");
        Path[] candidates = new Path[]{
                Paths.get(p),
                Paths.get(System.getProperty("user.dir"), p),
                Paths.get(System.getProperty("user.dir")).getParent() == null
                        ? null : Paths.get(System.getProperty("user.dir")).getParent().resolve(p),
                Paths.get(System.getProperty("user.dir"), "src", "main", "resources", p)
        };
        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                return c;
            }
        }
        try {
            java.net.URL url = getClass().getClassLoader().getResource(baseName(p));
            if (url != null) {
                return Paths.get(url.toURI());
            }
        } catch (Exception ignored) {
            // 忽略
        }
        return null;
    }

    private static String baseName(String p) {
        int i = p.lastIndexOf('/');
        return i < 0 ? p : p.substring(i + 1);
    }
}
