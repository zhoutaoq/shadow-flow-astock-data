package io.github.zhoutaoq.shadowflow.biz.migrate;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * 数据迁移：把选定维度打包成 zip 下载，或把别的环境导出的 zip 按增量导进来。
 *
 * <p><b>包结构</b>（一个维度一个文件，键名与数据库列名一致）：
 * <pre>
 * manifest.json   清单：格式版本、导出时间、存储模式、每个维度的行数
 * users.json      用户账号
 * sys_config.json 系统配置
 * stock_list.json 股票列表
 * kline.json      日K（仅数据库模式）
 * account.json    账户
 * position.json   持仓
 * trade_record.json 操作记录
 * account_trend.json 账户走势
 * backtest.json   回测（主记录 + 每日快照 + 成交明细）
 * </pre>
 *
 * <p>manifest 最后写：清单里有行数，行数要等数据写完才知道；zip 允许条目任意顺序，
 * 读的时候按名字找，不依赖顺序。
 *
 * <p><b>为什么是 zip 而不是单文件</b>：日K可能有上百万行，单文件意味着必须先拼出
 * 一大块内存再吐给浏览器；zip 允许一个维度一个条目、流式写、流式读，
 * 服务端内存占用与数据量无关。
 *
 * <p><b>导入顺序</b>由 {@link MigrateDimension#getOrder()} 决定，不看 zip 里的条目顺序 ——
 * 持仓要挂在账户上、快照要挂在回测上，顺序错了就会得到一堆指不到父行的孤儿数据。
 */
@Service
public class DataMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationService.class);

    /** 包格式版本：以后字段有增删时靠它决定怎么读旧包 */
    private static final int FORMAT_VERSION = 1;

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MigrationStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    public DataMigrationService(MigrationStore store) {
        this.store = store;
    }

    /** 页面上的维度清单：名称、说明、当前行数、是否可用 */
    public List<Map<String, Object>> dimensions() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (MigrateDimension d : ordered()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.getKey());
            m.put("label", d.getLabel());
            m.put("description", d.getDescription());
            m.put("available", store.available(d));
            m.put("rows", store.count(d));
            out.add(m);
        }
        return out;
    }

    /** 导出文件名：带时间戳，避免同一天导两次分不清 */
    public String exportFileName() {
        return "shadowflow-data-" + LocalDateTime.now().format(TS) + ".zip";
    }

    /**
     * 把选中的维度写成一个 zip。
     *
     * @param keys 维度 key；空 = 全部可用的维度
     */
    public void export(List<String> keys, OutputStream out) throws IOException {
        List<MigrateDimension> dims = selected(keys);
        List<Map<String, Object>> manifestDims = new ArrayList<>();
        ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out), StandardCharsets.UTF_8);
        for (MigrateDimension d : dims) {
            if (!store.available(d)) {
                continue;
            }
            ZipEntry entry = new ZipEntry(d.getKey() + ".json");
            zip.putNextEntry(entry);
            JsonGenerator gen = mapper.getFactory().createGenerator(zip);
            // 别让生成器把 zip 流一起关掉：后面还要写下一个条目与清单
            gen.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            gen.writeStartArray();
            store.exportTo(d, gen);
            gen.writeEndArray();
            gen.close();
            zip.closeEntry();

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", d.getKey());
            m.put("label", d.getLabel());
            m.put("rows", store.count(d));
            manifestDims.add(m);
            log.info("[migrate] 已导出 {}（{} 行）", d.getLabel(), m.get("rows"));
        }
        // 清单最后写：行数要等数据写完才知道
        zip.putNextEntry(new ZipEntry("manifest.json"));
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("formatVersion", FORMAT_VERSION);
        manifest.put("app", "影流数据");
        manifest.put("appVersion", appVersion());
        manifest.put("exportedAt", LocalDateTime.now().format(ISO));
        manifest.put("storageMode", store.mode());
        ArrayNode arr = manifest.putArray("dimensions");
        for (Map<String, Object> m : manifestDims) {
            ObjectNode n = arr.addObject();
            n.put("key", String.valueOf(m.get("key")));
            n.put("label", String.valueOf(m.get("label")));
            n.put("rows", ((Number) m.get("rows")).longValue());
        }
        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
        zip.closeEntry();
        zip.finish();
        zip.flush();
    }

    /**
     * 导入一个数据包。
     *
     * @return {mode, formatVersion, exportedAt, results:[{key,label,rows,inserted,updated,unchanged,skipped,message}], total:{...}}
     */
    public Map<String, Object> importZip(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要导入的数据包（zip）");
        }
        Path tmp = Files.createTempFile("shadowflow-import-", ".zip");
        try {
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            try (ZipFile zip = new ZipFile(tmp.toFile(), StandardCharsets.UTF_8)) {
                JsonNode manifest = readManifest(zip);
                int version = manifest.path("formatVersion").asInt(0);
                if (version <= 0) {
                    throw new IllegalArgumentException("数据包缺少清单，不是本系统导出的备份");
                }
                if (version > FORMAT_VERSION) {
                    throw new IllegalArgumentException("数据包格式版本 " + version
                            + " 高于当前系统支持的 " + FORMAT_VERSION + "，请先升级系统再导入");
                }

                List<Map<String, Object>> results = new ArrayList<>();
                long totalRows = 0, totalInserted = 0, totalUpdated = 0, totalUnchanged = 0;
                for (MigrateDimension d : ordered()) {
                    ZipEntry entry = zip.getEntry(d.getKey() + ".json");
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("key", d.getKey());
                    r.put("label", d.getLabel());
                    if (entry == null) {
                        continue;
                    }
                    if (!store.available(d)) {
                        r.put("skipped", true);
                        r.put("message", "当前存储模式不落这类数据，已跳过（K线在文件模式下按需实时取数）");
                        results.add(r);
                        continue;
                    }
                    ImportStat stat;
                    try (InputStream in = zip.getInputStream(entry)) {
                        JsonParser parser = mapper.getFactory().createParser(in);
                        parser.disable(JsonParser.Feature.AUTO_CLOSE_SOURCE);
                        stat = store.importFrom(d, parser);
                        parser.close();
                    }
                    r.put("rows", stat.getRows());
                    r.put("inserted", stat.getInserted());
                    r.put("updated", stat.getUpdated());
                    r.put("unchanged", stat.getUnchanged());
                    results.add(r);
                    totalRows += stat.getRows();
                    totalInserted += stat.getInserted();
                    totalUpdated += stat.getUpdated();
                    totalUnchanged += stat.getUnchanged();
                    log.info("[migrate] 已导入 {}：{} 行（新增 {}，覆盖 {}，未变 {}）",
                            d.getLabel(), stat.getRows(), stat.getInserted(), stat.getUpdated(), stat.getUnchanged());
                }
                if (results.isEmpty()) {
                    throw new IllegalArgumentException("数据包里没有任何可识别的数据维度");
                }
                // 文件模式：让仓储把内存状态重新载一遍，否则页面看到的还是导入前那一份
                store.reloadAfterImport();

                Map<String, Object> out = new LinkedHashMap<>();
                out.put("mode", store.mode());
                out.put("formatVersion", version);
                out.put("exportedAt", manifest.path("exportedAt").asText(""));
                out.put("sourceMode", manifest.path("storageMode").asText(""));
                out.put("results", results);
                Map<String, Object> total = new LinkedHashMap<>();
                total.put("rows", totalRows);
                total.put("inserted", totalInserted);
                total.put("updated", totalUpdated);
                total.put("unchanged", totalUnchanged);
                out.put("total", total);
                return out;
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.debug("[migrate] 临时文件清理失败 {}：{}", tmp, e.getMessage());
            }
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private JsonNode readManifest(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry("manifest.json");
        if (entry == null) {
            throw new IllegalArgumentException("数据包里没有清单文件（manifest.json），不是本系统导出的备份");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return mapper.readTree(in);
        }
    }

    /** 选中的维度：按依赖顺序（账户在持仓前、回测主记录在快照前）排好 */
    private List<MigrateDimension> selected(List<String> keys) {
        List<MigrateDimension> out = new ArrayList<>();
        if (keys == null || keys.isEmpty()) {
            for (MigrateDimension d : ordered()) {
                if (store.available(d)) {
                    out.add(d);
                }
            }
            return out;
        }
        for (String k : keys) {
            MigrateDimension d = MigrateDimension.of(k);
            if (d != null && !out.contains(d)) {
                out.add(d);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个数据维度");
        }
        out.sort(Comparator.comparingInt(MigrateDimension::getOrder));
        return out;
    }

    private static List<MigrateDimension> ordered() {
        List<MigrateDimension> all = new ArrayList<>();
        for (MigrateDimension d : MigrateDimension.values()) {
            all.add(d);
        }
        all.sort(Comparator.comparingInt(MigrateDimension::getOrder));
        return all;
    }

    /** jar 清单里的版本号；IDE 直跑时没有实现版本，退成 dev */
    private String appVersion() {
        Package pkg = DataMigrationService.class.getPackage();
        String v = pkg == null ? null : pkg.getImplementationVersion();
        return v == null || v.isEmpty() ? "dev" : v;
    }
}
