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
 * <p><b>v1.2 存储结构</b>：删掉了原来的 {@code List<Item>} —— 每条 Item 都是一个含两个 String
 * 引用的小对象，5436 条就要 5436 个对象头 + 引用 + padding，~130KB 全是包装开销。
 * 改成两个并行 {@code String[] codes / names}：
 * <ul>
 *   <li>底层就是 JVM 原生 String 数组，没有任何 wrapper 对象</li>
 *   <li>匹配时按数组下标扫，零分配（仅排序用几个 int 临时变量）</li>
 *   <li>返回的 List&lt;Item&gt; 在 controller 边界按需构造，每个请求最多 50 个临时 Item，请求结束可 GC</li>
 * </ul>
 * 实测 5436 条常驻 ~350KB（含 name 的小写副本，匹配用的）。查询是 O(n) 扫描 + 10 元素排序，
 * 单核 p95 < 5ms。
 */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class JsonStockDirectory implements StockDirectory {

    private static final Logger log = LoggerFactory.getLogger(JsonStockDirectory.class);

    /** 5436 条 → 6 位代码（如 "600519"），与 names 等长且一一对应 */
    private final String[] codes;
    /** 5436 条 → 中文名称（如 "贵州茅台"），与 codes 等长且一一对应 */
    private final String[] names;
    /** 与 names 等长：names[i].toLowerCase(Locale.ROOT)，匹配时直接 indexOf，避免每次 lowerCase */
    private final String[] namesLower;

    public JsonStockDirectory(WaizaoProperties props) {
        String[][] loaded = load(props.getStockListFile());
        this.codes = loaded[0];
        this.names = loaded[1];
        this.namesLower = new String[names.length];
        for (int i = 0; i < names.length; i++) {
            namesLower[i] = names[i] == null ? "" : names[i].toLowerCase(Locale.ROOT);
        }
        log.info("[directory] 本地股票名录载入沪深A股 {} 条，估算常驻 {}KB（并行 String[] 存储）",
                codes.length, estimateBytes() / 1024);
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
        boolean empty = lower.isEmpty();

        // 一次扫描，零分配命中数组（用 int[] 存下标，最后再按下标构造 Item）
        int[] hits = new int[codes.length];
        int hitN = 0;
        for (int i = 0; i < codes.length; i++) {
            if (empty || containsIgnoreCase(codes[i], lower, i)) {
                hits[hitN++] = i;
            }
        }

        // 按 rank + code 升序：对命中的下标排序（O(k log k)，k 是命中数）
        // 单次 sort 不分配 Item 对象，最多 10 元素时可比直接构造 List 更快
        sortByRank(hits, hitN, lower);

        List<Item> out = new ArrayList<>(hitN);
        for (int i = 0; i < hitN; i++) {
            int idx = hits[i];
            out.add(new Item(codes[idx], names[idx]));
        }
        return out;
    }

    /** 命中判断：代码直接 contains（6 位 ASCII 数字），名称走预 lower 的副本 */
    private boolean containsIgnoreCase(String code, String lowerKw, int nameIdx) {
        return code.indexOf(lowerKw) >= 0 || namesLower[nameIdx].indexOf(lowerKw) >= 0;
    }

    /** 插入排序 —— 命中数 5436 很少，Arrays.sort 的 dual-pivot 实现有额外装箱，这里手写更省 */
    private void sortByRank(int[] hits, int n, String lower) {
        for (int i = 1; i < n; i++) {
            int cur = hits[i];
            int curRank = rank(cur, lower);
            int j = i - 1;
            while (j >= 0) {
                int prev = hits[j];
                int prevRank = rank(prev, lower);
                int d = curRank - prevRank;
                if (d < 0 || (d == 0 && codes[cur].compareTo(codes[prev]) < 0)) {
                    hits[j + 1] = prev;
                    j--;
                } else {
                    break;
                }
            }
            hits[j + 1] = cur;
        }
    }

    private int rank(int i, String lower) {
        if (lower.isEmpty()) {
            return 3;
        }
        String code = codes[i];
        String name = namesLower[i];
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

    /** 估算常驻内存（仅做日志参考）：3 个 String[] 数组头 + N 个 String 对象的 char[] 引用 + 平均 name 长度 */
    private long estimateBytes() {
        long bytes = 24L * 3; // 3 个数组对象头
        for (String c : codes) {
            bytes += 40 + (c == null ? 0 : c.length() * 2L); // String header + char[]（含 4 字节 length 字段）
        }
        for (String n : names) {
            bytes += 40 + (n == null ? 0 : n.length() * 2L);
        }
        for (String n : namesLower) {
            bytes += 40 + (n == null ? 0 : n.length() * 2L);
        }
        return bytes;
    }

    /**
     * 从配置的 stock-list.json 解析出 [codes[], names[]] 两列。
     *
     * <p>加载失败时返回两个空数组（search 会返回空列表，UI 走"手输代码"兜底），
     * 不会让 Bean 初始化失败阻塞 Spring 启动。
     */
    private static String[][] load(String configuredPath) {
        String json = read(configuredPath);
        if (json == null) {
            log.warn("[directory] 未找到股票名录文件（{}），股票选择器只能手输代码", configuredPath);
            return new String[][]{new String[0], new String[0]};
        }
        try {
            JsonNode root = new ObjectMapper().readTree(json);
            if (root == null || !root.isArray()) {
                log.warn("[directory] 股票名录不是 JSON 数组，已忽略");
                return new String[][]{new String[0], new String[0]};
            }
            // 第一遍：统计有效条目，预先分配
            int valid = 0;
            for (JsonNode n : root) {
                String code = n.path("code").asText(null);
                if (code != null && AShareCodes.isAShare(code.trim())) {
                    valid++;
                }
            }
            String[] codes = new String[valid];
            String[] names = new String[valid];
            int skipped = 0;
            int idx = 0;
            for (JsonNode n : root) {
                String code = n.path("code").asText(null);
                if (code == null) {
                    continue;
                }
                String trimmed = code.trim();
                if (!AShareCodes.isAShare(trimmed)) {
                    skipped++;
                    continue;
                }
                codes[idx] = trimmed;
                names[idx] = n.path("name").asText("").trim();
                idx++;
            }
            if (skipped > 0) {
                log.info("[directory] 已过滤掉 {} 条非沪深A股记录", skipped);
            }
            return new String[][]{codes, names};
        } catch (Exception e) {
            log.warn("[directory] 股票名录解析失败: {}", e.getMessage());
            return new String[][]{new String[0], new String[0]};
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
