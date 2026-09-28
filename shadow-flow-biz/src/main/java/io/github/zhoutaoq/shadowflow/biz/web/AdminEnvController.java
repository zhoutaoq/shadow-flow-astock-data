package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.config.EnvViewService;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 环境查看（v1.6.1）：只读，给超管核对「这台机器上服务实际拿到了哪些配置」。
 *
 * <p>v1.6.4 起密钥（classpath app.env）由启动类装成系统属性、不再走环境变量；
 * 运行参数（server.env）仍由 remote.sh 注入进程环境。仓库干净了，但线上
 * 有没有装进来就看不见了 —— 这个接口把那层补上：凭据一律脱敏，只露前后各 4 位；
 * 运行参数明文，用来核对本次发布带上了哪些启动参数。
 *
 * <p>只有读、没有写：密钥改在 src/main/resources/app.env（不入库）里改，重新打包发布即可 ——
 * 页面能改等于给口令开了第二个写入口，两边不一致时反而更难查。
 *
 * <p>只有超管能看：这些是平台自己的凭据。路径挂在 {@code /api/admin/**} 下，
 * 拦截器已保证「已登录」，超管身份这里再判一次。
 */
@RestController
@RequestMapping("/api/admin/env")
public class AdminEnvController {

    private final EnvViewService service;

    public AdminEnvController(EnvViewService service) {
        this.service = service;
    }

    /** 当前服务生效的环境变量清单：secrets（脱敏）+ runtimes（明文） */
    @GetMapping
    public ApiResponse<Map<String, Object>> view() {
        requireAdmin();
        return ApiResponse.ok(service.view());
    }

    private void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以查看服务环境变量");
        }
    }
}
