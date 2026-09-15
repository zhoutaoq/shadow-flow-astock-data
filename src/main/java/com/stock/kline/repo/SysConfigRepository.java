package com.stock.kline.repo;

import com.stock.kline.model.SysConfig;

import java.util.List;

/**
 * 通用配置（归属用户 + 场景 + 键 → 值）的统一仓储。
 *
 * <p>v1.3：模型与业务代码只有一份，落点由装配的策略决定 ——
 * 数据库模式写 {@code waizao_sys_config}，无数据库模式写 {@code ./data/sys-config.json}。
 *
 * <p>v1.5：配置按用户隔离，所有读写都要带 {@code ownerId}。令牌这类凭据
 * 「谁配的谁用」，页面只维护当前登录用户自己的行；取不到时由调用方回落到
 * {@link SysConfig#SYSTEM_OWNER}（系统级兜底）。
 *
 * <p>这里只放运行期会变的变量（目前是 waizao 的 token）；接口地址、超时、并发度
 * 这些稳定参数仍在 application.yml，改了重启生效。
 */
public interface SysConfigRepository {

    /** 场景：waizao 供应商 */
    String SCENE_WAIZAO = "waizao";
    /** waizao 场景下的接口令牌 */
    String KEY_TOKEN = "token";
    /** 系统级归属（= {@link SysConfig#SYSTEM_OWNER}），未登录 / 未单独配置时的兜底 */
    long SYSTEM_OWNER = SysConfig.SYSTEM_OWNER;

    /** 取值，没有返回 null */
    String get(long ownerId, String scene, String key);

    /** 取值，没有返回默认值 */
    String get(long ownerId, String scene, String key, String def);

    /** 系统级值（ownerId = 0），没有返回 null */
    String get(String scene, String key);

    /** 指定用户生效的 waizao token，没配过返回空串 */
    String waizaoToken(long ownerId);

    /** 系统级 waizao token（ownerId = 0），没配过返回空串 */
    String waizaoToken();

    /** 指定用户名下的全部配置项 */
    List<SysConfig> list(long ownerId);

    /** 新增或更新（按 ownerId + scene + key 定位），保存后立即生效 */
    void save(SysConfig cfg);

    /** 删除指定用户名下的配置项；不是自己的行删不动 */
    void delete(long ownerId, long id);

    /** 从落点重新加载（手工改了库或文件时调用） */
    void reload();
}
