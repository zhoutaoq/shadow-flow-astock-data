package com.stock.kline.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.StockListItem;
import com.stock.kline.support.AShareCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 从 resources/stock-list.json 导入股票列表到 waizao_stock_list */
@Service
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class StockListImportService {

    private static final Logger log = LoggerFactory.getLogger(StockListImportService.class);

    private final StockListService stockListService;
    private final WaizaoProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public StockListImportService(StockListService stockListService, WaizaoProperties properties) {
        this.stockListService = stockListService;
        this.properties = properties;
    }

    /**
     * 导入股票列表，只收沪深A股（其余代码整条丢掉）。
     *
     * @param filePath 文件路径，为空则用配置里的 waizao.stock-list-file
     * @return 解析出并写入的条数
     */
    public int importFromFile(String filePath) {
        Path path = resolve(filePath);
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("股票列表文件不存在: " + filePath);
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(content);
            if (root == null || !root.isArray()) {
                throw new IllegalArgumentException("文件内容不是 JSON 数组: " + path);
            }
            List<StockListItem> items = new ArrayList<>();
            int skipped = 0;
            for (JsonNode n : root) {
                String code = n.path("code").asText(null);
                String name = n.path("name").asText(null);
                if (code == null || code.trim().isEmpty()) {
                    continue;
                }
                String trimmed = code.trim();
                if (!AShareCodes.isAShare(trimmed)) {
                    skipped++;
                    continue;
                }
                items.add(new StockListItem(trimmed, name == null ? "" : name.trim(), "stock"));
            }
            int affected = stockListService.upsertBatch(items);
            log.info("[import] file={} parsed={} affected={} skippedNotAShare={}",
                    path, items.size(), affected, skipped);
            return items.size();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("导入失败: " + e.getMessage(), e);
        }
    }

    /** 依次尝试：绝对路径 / 工作目录相对路径 / 项目根目录相对路径 / classpath */
    private Path resolve(String filePath) {
        String p = (filePath == null || filePath.trim().isEmpty())
                ? properties.getStockListFile() : filePath.trim();
        List<Path> candidates = new ArrayList<>();
        candidates.add(Paths.get(p));
        candidates.add(Paths.get(System.getProperty("user.dir"), p));
        candidates.add(Paths.get(System.getProperty("user.dir")).getParent().resolve(p));
        candidates.add(Paths.get(System.getProperty("user.dir"), "src", "main", "resources", p));

        // classpath 兜底
        try {
            File f = new File(getClass().getClassLoader().getResource(p.replace("\\", "/")).toURI());
            candidates.add(f.toPath());
        } catch (Exception ignored) {
            // ignore
        }

        for (Path c : candidates) {
            if (c != null && Files.isRegularFile(c)) {
                return c.toAbsolutePath().normalize();
            }
        }
        return null;
    }
}
