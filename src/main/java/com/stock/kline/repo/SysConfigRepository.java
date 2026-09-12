package com.stock.kline.repo;

import com.stock.kline.model.SysConfig;

import java.util.List;

/**
 * 通用配置（场景 + 键 → 值）的统一仓储。
 *
 * <p>v1.3：模型与业务代码只有一份，落点由装配的策略决定 ——
 * 数据库模式写 {@code waizao_sys_config}，无数据库模式写 {@code ./data/sys-config.json}。
 *
 * <p>这里只放运行期会变的变量（目前是 waizao 的 token）；接口地址、超时、并发度
 * 这些稳定参数仍在 application.yml，改了重启生效。
 */
public interface SysConfigRepository {

    /** 场景：waizao 供应商 */
    String SCENE_WAIZAO = "waizao";
    /** waizao 场景下的接口令牌 */
    String KEY_TOKEN = "token";

    /** 取值，没有返回 null */
    String get(String scene, String key);

    /** 取值，没有返回默认值 */
    String get(String scene, String key, String def);

    /** waizao 供应商当前生效的 token */
    String waizaoToken();

    /** 全部配置项 */
    List<SysConfig> list();

    /** 新增或更新（按 scene + key 定位），保存后立即生效 */
    void save(SysConfig cfg);

    /** 删除配置项 */
    void delete(long id);

    /** 从落点重新加载（手工改了库或文件时调用） */
    void reload();
}
