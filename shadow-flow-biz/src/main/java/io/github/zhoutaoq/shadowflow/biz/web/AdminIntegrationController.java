package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.config.IntegrationConfigService;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.IntegrationSaveRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 第三方对接配置（v1.5.9）：行情数据服务令牌、钉钉机器人、反馈与注册的关键词。
 *
 * <p>改造前这些凭据分三处维护 —— 行情数据服务令牌按用户各存一份、钉钉机器人存在盯盘的个人落盘文件里、
 * 反馈与注册的关键词只能改 application.yml 重启。现在统一收在这一个接口下，
 * 存系统级配置行（{@code ownerId = 0}），<b>全局一份、全站生效</b>。
 *
 * <p>只有超管能看能改：这些是系统对接第三方用的凭据，普通用户既不该看到、也不该改。
 * 路径挂在 {@code /api/admin/**} 下，拦截器已保证「已登录」，超管身份这里再判一次。
 *
 * <p>读接口返回的值一律打码（令牌与 webhook 只露前后各 4 位），
 * 关键词这类非凭据才回显明文。
 */
@RestController
@RequestMapping("/api/admin/integration")
public class AdminIntegrationController {

    private final IntegrationConfigService service;

    public AdminIntegrationController(IntegrationConfigService service) {
        this.service = service;
    }

    /** 当前生效的对接配置总览（含每一项的值来自配置行还是 yml 兜底） */
    @GetMapping
    public ApiResponse<Map<String, Object>> view() {
        requireAdmin();
        return ApiResponse.ok(service.view());
    }

    /** 保存：留空 = 该项不动，要清空走 clear 名单；保存后立即生效，不用重启 */
    @PostMapping
    public ApiResponse<Map<String, Object>> save(@RequestBody IntegrationSaveRequest req) {
        requireAdmin();
        if (req == null) {
            throw new IllegalArgumentException("请求体不能为空");
        }
        return ApiResponse.ok(service.save(req.getValues(), req.getClear()), "已保存并生效");
    }

    /** 全部恢复出厂值：删掉系统级对接配置行，改回读 application.yml */
    @PostMapping("/reset")
    public ApiResponse<Map<String, Object>> reset() {
        requireAdmin();
        return ApiResponse.ok(service.reset(), "已恢复出厂值");
    }

    private void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以修改系统对接配置");
        }
    }
}
