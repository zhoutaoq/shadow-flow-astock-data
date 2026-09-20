package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.service.LogoService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统 logo 的上传与恢复出厂（需求 v1.6.2 第 2 条）：只有超管能改。
 *
 * <p>与其它系统配置同一口径：路径挂在 {@code /api/admin/**} 下，
 * 拦截器已保证「已登录」，超管身份这里再判一次。
 *
 * <p>读写在两个 controller 上：读取是匿名的（见 {@link LogoController}），
 * 写入在这里 —— 把它们分开是因为「谁能改」和「谁能看」本来就不同，
 * 混在一个类里迟早会有人给读接口顺手加上超管校验，页面就直接白屏了。
 */
@RestController
@RequestMapping("/api/admin/logo")
public class AdminLogoController {

    private final LogoService service;

    public AdminLogoController(LogoService service) {
        this.service = service;
    }

    /** 上传新 logo：落盘即生效，全站下一次加载就是新图 */
    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file) {
        requireAdmin();
        String url = service.save(file);
        return ApiResponse.ok(payload(url), "Logo 已更新");
    }

    /** 恢复出厂：删掉上传的那份，回到打包里的默认 logo */
    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset() {
        requireAdmin();
        String url = service.reset();
        return ApiResponse.ok(payload(url), "已恢复默认 Logo");
    }

    private Map<String, Object> payload(String url) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", url);
        data.put("customized", service.customized());
        return data;
    }

    private void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以修改系统配置");
        }
    }
}
