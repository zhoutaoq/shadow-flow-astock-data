package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.service.LogoService;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统 logo 的读取接口（需求 v1.6.2 第 2 条）。
 *
 * <p>两个接口都<b>必须免登录</b>：
 * <ul>
 *   <li>{@code GET /api/logo} —— 全站每个页面（含登录页、首页这些匿名入口）都要读它
 *       才知道顶栏该挂哪张图，只回一个地址，不含任何账号数据；</li>
 *   <li>{@code GET /api/logo/view} —— 自定义 logo 的图片本体。前端 {@code <img>} 直接引用它，
 *       浏览器发的是不带自定义头的普通请求，拦在登录后面图片就加载不出来。</li>
 * </ul>
 * 上传是超管专属，在 {@link AdminLogoController}。
 */
@RestController
@RequestMapping("/api/logo")
public class LogoController {

    private final LogoService service;

    public LogoController(LogoService service) {
        this.service = service;
    }

    /** 当前生效的 logo 地址 + 是否自定义 */
    @GetMapping
    public ApiResponse<Map<String, Object>> current() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("url", service.currentUrl());
        data.put("customized", service.customized());
        return ApiResponse.ok(data);
    }

    /**
     * 自定义 logo 的图片本体；没有自定义时 404。
     *
     * <p>缓存策略与静态资源一致（7 天）：换图时地址里的 {@code v} 参数会变，
     * 不存在拿到旧图的问题，而 logo 是每页都要加载的资源，不能让它每次重下。
     */
    @GetMapping("/view")
    public ResponseEntity<Resource> view() {
        Path file = service.findCustom();
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = MediaType.IMAGE_JPEG;
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".png")) {
            type = MediaType.IMAGE_PNG;
        } else if (name.endsWith(".webp")) {
            type = MediaType.parseMediaType("image/webp");
        } else if (name.endsWith(".gif")) {
            type = MediaType.IMAGE_GIF;
        }
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofDays(7)).cachePublic())
                .body(new FileSystemResource(file));
    }
}
