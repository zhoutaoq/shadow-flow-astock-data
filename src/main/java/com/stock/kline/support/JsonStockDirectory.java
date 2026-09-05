package com.stock.kline.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 无数据库模式：候选来自本地 stock-list.json，启动后常驻内存。
 *
 * <p>只收沪深A股（6 位数字且以 60/68/00/30 开头），其余代码（5 位港股、1/5 开头基金债券、
 * 9 开头北交所）在载入阶段就丢掉 —— 系统只做沪深A股，留着只会污染候选列表。
 * 判断口径见 {@link AShareCodes}。
 *
 * <p>匹配是纯内存前缀/包含比较，输入即时出结果；全量几 MB 常驻内存，比每次请求都读磁盘划算。
 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class JsonStockDirectory implements StockDirectory {

    private static final Logger log = LoggerFactory.getLogger(JsonStockDirectory.class);

    private final List<Item> items;

    public JsonStockDirectory(WaizaoProperties props) {
        this.items = load(props.getStockListFile());
        log.info("[directory] 本地股票名录载入沪深A股 {} 条", items.size());
    }

    @Override
    public List<Item> search(String keyword, int limit) {
        List<Item> hit = match(keyword);
        if (hit.size() > limit) {
            return new ArrayList<>(hit.subList(0, limit));
        }
        return hit;
    }

    /**
     * 无数据库模式的股票列表页直接翻内存里的名录。
     *
     * <p>关键字为空时等于整份名录（6000+ 条），靠分页切片避免一次塞满页面。
     */
    @Override
    public PageResult<Item> page(String keyword, int page, int size) {
        List<Item> hit = match(keyword);
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : size;
        int from = Math.min((p - 1) * s, hit.size());
        int to = Math.min(from + s, hit.size());
        return new PageResult<>(new ArrayList<>(hit.subList(from, to)), hit.size(), p, s);
    }

    /** 按关键字过滤并排序（完全相等 → 代码前缀 → 名称前缀 → 其余，同档按代码升序） */
    private List<Item> match(String keyword) {
        String kw = keyword == null ? "" : keyword.trim();
        String lower = kw.toLowerCase(Locale.ROOT);

        List<Item> hit = new ArrayList<>();
        for (Item it : items) {
            if (lower.isEmpty()) {
                hit.add(it);
                continue;
            }
            String code = it.getCode() == null ? "" : it.getCode();
            String name = it.getName() == null ? "" : it.getName();
            if (code.contains(lower) || name.toLowerCase(Locale.ROOT).contains(lower)) {
                hit.add(it);
            }
        }
        hit.sort(Comparator
                .comparingInt((Item it) -> rank(it, lower))
                .thenComparing(it -> it.getCode() == null ? "" : it.getCode()));
        return hit;
    }

    private static int rank(Item it, String lower) {
        if (lower.isEmpty()) {
            return 3;
        }
        String code = it.getCode() == null ? "" : it.getCode();
        String name = it.getName() == null ? "" : it.getName().toLowerCase(Locale.ROOT);
        if (code.equalsIgnoreCase(lower)) {
            return 0;
        }
        if (code.toLowerCase(Locale.ROOT).startsWith(lower)) {
            return 1;
        }
        if (name.startsWith(lower)) {
            return 2;
        }
        return 3;
    }

    private static List<Item> load(String configuredPath) {
        String json = read(configuredPath);
        if (json == null) {
            log.warn("[directory] 未找到股票名录文件（{}），股票选择器只能手输代码", configuredPath);
            return Collections.emptyList();
        }
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (root == null || !root.isArray()) {
                log.warn("[directory] 股票名录不是 JSON 数组，已忽略");
                return Collections.emptyList();
            }
            List<Item> list = new ArrayList<>(root.size());
            int skipped = 0;
            for (JsonNode n : root) {
                String code = n.path("code").asText(null);
                if (code == null || code.trim().isEmpty()) {
                    continue;
                }
                String trimmed = code.trim();
                if (!AShareCodes.isAShare(trimmed)) {
                    skipped++;
                    continue;
                }
                String name = n.path("name").asText("");
                list.add(new Item(trimmed, name.trim()));
            }
            if (skipped > 0) {
                log.info("[directory] 已过滤掉 {} 条非沪深A股记录", skipped);
            }
            return list;
        } catch (Exception e) {
            log.warn("[directory] 股票名录解析失败: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /** 依次尝试：配置路径 → 工作目录相对 → 项目根相对 → classpath → classpath 下的文件名 */
    private static String read(String configuredPath) {
        String p = StringUtils.hasText(configuredPath) ? configuredPath.trim() : "resources/stock-list.json";
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(p));
        candidates.add(Paths.get(System.getProperty("user.dir"), p));
        Path parent = Paths.get(System.getProperty("user.dir")).getParent();
        if (parent != null) {
            candidates.add(parent.resolve(p));
        }
        candidates.add(Paths.get(System.getProperty("user.dir"), "src", "main", "resources", p));

        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                try {
                    return new String(Files.readAllBytes(c), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn("[directory] 读取失败 {}: {}", c, e.getMessage());
                }
            }
        }
        // classpath 兜底：打进 jar 后文件在 classpath 根目录
        for (String cp : new String[]{p.replace("\\", "/"), baseName(p)}) {
            try {
                ClassPathResource res = new ClassPathResource(cp);
                if (!res.exists()) {
                    continue;
                }
                try (InputStream in = res.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    StringBuilder sb = new StringBuilder();
                    while ((n = in.read(buf)) > 0) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                    return sb.toString();
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
        return null;
    }

    private static String baseName(String p) {
        String s = p.replace("\\", "/");
        int i = s.lastIndexOf('/');
        return i < 0 ? s : s.substring(i + 1);
    }
}
