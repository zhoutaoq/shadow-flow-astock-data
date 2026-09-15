package com.stock.kline.web;

import com.stock.kline.auth.AuthContext;
import com.stock.kline.auth.model.User;
import com.stock.kline.model.ApiResponse;
import com.stock.kline.model.SysConfig;
import com.stock.kline.model.SysConfigSaveRequest;
import com.stock.kline.repo.SysConfigRepository;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 「实时通知配置」接口：维护运行期变量（当前是 waizao 的 token），按用户隔离。
 *
 * <p>v1.5 起的口径：
 * <ul>
 *   <li>普通用户只看得到、也只改得动自己名下的行；</li>
 *   <li><b>超管额外能管理系统级</b>（{@code ownerId = 0}）那一份 —— 它是未登录请求
 *       （首页、K线查看）和还没配过自己令牌的用户的兜底，供应商令牌轮换时必须能改，
 *       所以不能只留「每人一份」；</li>
 *   <li>保存时归属由服务端裁决：非超管一律写自己名下，超管按请求体里的归属走。</li>
 * </ul>
 * 换句话说，普通用户在接口层面拿不到、也改不了别人的令牌。
 *
 * <p>整个前缀已登记在 {@code AuthWebConfig.PROTECTED}，未登录访问直接 401。
 */
@RestController
@RequestMapping("/api/sys-config")
public class SysConfigController {

    private final SysConfigRepository repository;

    public SysConfigController(SysConfigRepository repository) {
        this.repository = repository;
    }

    /** 我名下的全部配置项；超管还能看到系统级那一份 */
    @GetMapping
    public ApiResponse<List<SysConfig>> list() {
        User me = AuthContext.current();
        if (me == null || me.getId() == null) {
            return ApiResponse.fail("请先登录");
        }
        List<SysConfig> all = new ArrayList<>(repository.list(me.getId()));
        if (me.isAdmin()) {
            all.addAll(repository.list(SysConfig.SYSTEM_OWNER));
        }
        return ApiResponse.ok(all);
    }

    /** 取我名下的单个配置值（空则返回空串） */
    @GetMapping("/{scene}/{key}")
    public ApiResponse<String> value(@PathVariable String scene, @PathVariable String key) {
        User me = AuthContext.current();
        if (me == null || me.getId() == null) {
            return ApiResponse.fail("请先登录");
        }
        return ApiResponse.ok(repository.get(me.getId(), scene, key, ""));
    }

    /** 新增或更新配置，保存后立即生效 */
    @PostMapping
    public ApiResponse<Void> save(@RequestBody SysConfigSaveRequest req) {
        User me = AuthContext.current();
        if (me == null || me.getId() == null) {
            return ApiResponse.fail("请先登录");
        }
        if (req == null) {
            return ApiResponse.fail("请求体不能为空");
        }
        // 归属由服务端裁决：只有超管能写系统级那一份，且必须是显式指定
        boolean system = me.isAdmin() && req.systemScoped();
        SysConfig cfg = req.toConfig();
        cfg.setOwnerId(system ? SysConfig.SYSTEM_OWNER : me.getId());
        try {
            repository.save(cfg);
            return ApiResponse.ok(null, "已保存并生效");
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("保存失败: " + StockListController.rootMsg(e));
        }
    }

    /** 删除配置项：自己的行随便删，超管还能删系统级的行 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        User me = AuthContext.current();
        if (me == null || me.getId() == null) {
            return ApiResponse.fail("请先登录");
        }
        try {
            long owner = me.isAdmin() && isSystemRow(id) ? SysConfig.SYSTEM_OWNER : me.getId();
            repository.delete(owner, id);
            return ApiResponse.ok(null, "已删除");
        } catch (Exception e) {
            return ApiResponse.fail("删除失败: " + StockListController.rootMsg(e));
        }
    }

    /** 从落点重新加载（手工改了库或文件时点一下） */
    @PostMapping("/reload")
    public ApiResponse<List<SysConfig>> reload() {
        repository.reload();
        return list();
    }

    /** 该 id 是否属于系统级那一份（系统级行很少，直接查一遍最省事也最直观） */
    private boolean isSystemRow(long id) {
        for (SysConfig c : repository.list(SysConfig.SYSTEM_OWNER)) {
            if (c.getId() != null && c.getId() == id) {
                return true;
            }
        }
        return false;
    }
}
