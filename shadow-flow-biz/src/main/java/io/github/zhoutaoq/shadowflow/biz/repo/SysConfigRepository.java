package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.SysConfig;

import java.util.List;

/**
 * 通用配置（归属用户 + 场景 + 键 → 值）的统一仓储。
 *
 * <p>v1.3：模型与业务代码只有一份，落点由装配的策略决定 ——
 * 数据库模式写 {@code shadowflow_sys_config}，无数据库模式写 {@code ./data/sys-config.json}。
 *
 * <p>v1.5：模型带上 {@code ownerId}，为「配置按用户隔离」留出结构。
 *
 * <p><b>v1.5.9：实际只使用系统级那一份</b>（{@link SysConfig#SYSTEM_OWNER}）。
 * 第三方对接配置（行情数据服务令牌、钉钉机器人、反馈与注册关键词）都是系统级设施，
 * 全局一份、全站共用，不再按用户各存一份 —— 凭据散在人头上，换一次要挨个通知，
 * 也容易在某个落盘文件里留下明文。{@code ownerId} 参数保留是为了将来真有
 * 「个人偏好」类配置时有地方放，现阶段调用方一律传 0。
 *
 * <p>这里只放运行期会变的变量；接口地址、超时、并发度
 * 这些稳定参数仍在 application.yml，改了重启生效。
 */
public interface SysConfigRepository {

    /**
     * 场景：行情数据服务凭据（令牌等）。
     *
     * <p>v1.6.0 起由 {@code waizao} 改名而来。启动时会就地把旧行的场景值改写成新值
     * （数据库模式见 {@code ShadowFlowDbInitializer}，文件模式见
     * {@code FileSysConfigRepository#migrateLegacyScene}），所以正常升级后库里不该再留旧值；
     * 读取时仍对新旧两个值都认（见 {@link #SCENE_DATA_API_LEGACY}），
     * 是为了兜住「迁移被跳过 / 有人手工塞回旧行」的情况 —— 少一层兜底就可能表现为行情取数失败，
     * 而日志上看不出原因。
     */
    String SCENE_DATA_API = "shadowflow";

    /** 旧场景值（v1.6.0 之前的写入口径）；仅用于读取兜底，新数据一律写 {@link #SCENE_DATA_API} */
    String SCENE_DATA_API_LEGACY = "waizao";
    /** 行情数据服务场景下的接口令牌 */
    String KEY_TOKEN = "token";
    /** 系统级归属（= {@link SysConfig#SYSTEM_OWNER}），未登录 / 未单独配置时的兜底 */
    long SYSTEM_OWNER = SysConfig.SYSTEM_OWNER;

    /** 取值，没有返回 null */
    String get(long ownerId, String scene, String key);

    /** 取值，没有返回默认值 */
    String get(long ownerId, String scene, String key, String def);

    /** 系统级值（ownerId = 0），没有返回 null */
    String get(String scene, String key);

    /** 系统级行情数据服务令牌（ownerId = 0），没配过返回空串；新旧场景值都认 */
    String dataApiToken();

    /** 指定用户名下的全部配置项 */
    List<SysConfig> list(long ownerId);

    /** 新增或更新（按 ownerId + scene + key 定位），保存后立即生效 */
    void save(SysConfig cfg);

    /** 删除指定用户名下的配置项；不是自己的行删不动 */
    void delete(long ownerId, long id);

    /** 从落点重新加载（手工改了库或文件时调用） */
    void reload();
}
