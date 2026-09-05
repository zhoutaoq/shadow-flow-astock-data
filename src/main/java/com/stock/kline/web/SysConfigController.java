package com.stock.kline.web;

import com.stock.kline.model.ApiResponse;
import com.stock.kline.model.SysConfig;
import com.stock.kline.service.SysConfigService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通用配置 waizao_sys_config：按场景维护运行期可变变量（当前是 waizao 的 token）。
 *
 * <p>本接口只在本地管理页面使用，配置值里可能有计费令牌，不要暴露到公网。
 */
@RestController
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
@RequestMapping("/api/sys-config")
public class SysConfigController {

    private final SysConfigService service;

    public SysConfigController(SysConfigService service) {
        this.service = service;
    }

    /** 全部配置项 */
    @GetMapping
    public ApiResponse<List<SysConfig>> list() {
        return ApiResponse.ok(service.list());
    }

    /** 取单个配置值（空则返回空串） */
    @GetMapping("/{scene}/{key}")
    public ApiResponse<String> value(@PathVariable String scene, @PathVariable String key) {
        return ApiResponse.ok(service.get(scene, key, ""));
    }

    /** 新增或更新，保存后立即生效 */
    @PostMapping
    public ApiResponse<Void> save(@RequestBody SysConfig cfg) {
        if (cfg == null) {
            return ApiResponse.fail("请求体不能为空");
        }
        try {
            service.save(cfg);
            return ApiResponse.ok(null, "已保存并生效");
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("保存失败: " + StockListController.rootMsg(e));
        }
    }

    /** 删除配置项 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        try {
            service.delete(id);
            return ApiResponse.ok(null, "已删除");
        } catch (Exception e) {
            return ApiResponse.fail("删除失败: " + StockListController.rootMsg(e));
        }
    }

    /** 从数据库重新加载（手工改了库时点一下） */
    @PostMapping("/reload")
    public ApiResponse<List<SysConfig>> reload() {
        service.reload();
        return ApiResponse.ok(service.list(), "已重新加载");
    }
}
