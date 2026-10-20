package io.github.zhoutaoq.shadowflow.biz.migrate;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 数据迁移接口（v1.7.4 第 2 条）—— 只有超管能用。
 *
 * <p>三个动作：
 * <ul>
 *   <li>{@code GET /dimensions} —— 维度清单与各自行数，页面用它画勾选框；</li>
 *   <li>{@code GET /export?keys=a,b} —— 按维度打包下载 zip；</li>
 *   <li>{@code POST /import}（multipart）—— 上传 zip，按增量导入、冲突以导入数据为准。</li>
 * </ul>
 *
 * <p>权限：路径落在 {@code /api/admin/**} 下，拦截器已经要求登录；这里再显式判一次超管 ——
 * 数据迁移是「能读全站所有用户数据 + 能覆盖」的接口，不能只靠路径前缀兜底。
 *
 * <p>导出走 {@link HttpServletResponse} 直接流式写，不先落临时文件：
 * 日K维度可能几十万行，落盘再读一遍纯属浪费。
 */
@RestController
@RequestMapping("/api/admin/migrate")
public class DataMigrationController {

    private static final Logger log = LoggerFactory.getLogger(DataMigrationController.class);

    private final DataMigrationService service;

    public DataMigrationController(DataMigrationService service) {
        this.service = service;
    }

    /** 维度清单（含当前行数） */
    @GetMapping("/dimensions")
    public ApiResponse<Map<String, Object>> dimensions() {
        requireAdmin();
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("dimensions", service.dimensions());
        return ApiResponse.ok(data);
    }

    /**
     * 导出 zip。
     *
     * @param keys 逗号分隔的维度 key；不传 = 当前模式下所有可导出的维度
     */
    @GetMapping("/export")
    public void export(@RequestParam(name = "keys", required = false) String keys,
                       HttpServletResponse response) throws IOException {
        requireAdmin();
        List<String> selected = keys == null || keys.trim().isEmpty()
                ? java.util.Collections.emptyList()
                : Arrays.asList(keys.split(","));
        String fileName = service.exportFileName();
        // 中文文件名按 RFC 5987 编码，避免部分浏览器把文件名吃掉
        String encoded = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20");
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + encoded + "\"; filename*=UTF-8''" + encoded);
        response.setHeader("Cache-Control", "no-store");
        try {
            service.export(selected, response.getOutputStream());
            response.flushBuffer();
            log.info("[migrate] 超管 {} 导出数据：{}", currentName(), keys);
        } catch (RuntimeException e) {
            log.error("[migrate] 导出失败", e);
            // 响应头已经发出去了，改不成 JSON；页面那头会因为拿到的不是 zip 而提示失败
            throw e;
        }
    }

    /**
     * 导入 zip。
     *
     * <p>返回每个维度的「新增 / 覆盖 / 未变」条数，页面照实展示 —— 覆盖了多少存量
     * 是判断要不要回滚的唯一依据，不能糊成一句「导入成功」。
     */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importZip(@RequestParam("file") MultipartFile file) throws IOException {
        requireAdmin();
        Map<String, Object> result = service.importZip(file);
        log.info("[migrate] 超管 {} 导入数据包 {}：{}", currentName(),
                file.getOriginalFilename(), result.get("total"));
        return ApiResponse.ok(result, "导入完成");
    }

    /** 当前操作者（日志用）；拿不到登录态时给个占位，日志里不出现 null */
    private static String currentName() {
        io.github.zhoutaoq.shadowflow.biz.auth.model.User u = AuthContext.current();
        return u == null || u.getUsername() == null ? "-" : u.getUsername();
    }

    private static void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以迁移数据");
        }
    }
}
