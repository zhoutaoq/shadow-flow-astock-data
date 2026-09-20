package io.github.zhoutaoq.shadowflow.biz.auth.repo;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;

/**
 * 系统用户的统一仓储。
 *
 * <p>与其它仓储一样只有一份接口、两种落点：
 * 数据库模式写 {@code shadowflow_user}，无数据库模式写 {@code ./data/users.json}，
 * 业务代码（{@code AuthService}）不感知差别。
 */
public interface UserRepository {

    /** 按用户名（邮箱）查；不存在返回 null */
    User findByUsername(String username);

    /** 按主键查；不存在返回 null */
    User findById(long id);

    /**
     * 新增或更新用户资料（不含密码）。
     *
     * @return 落库后的用户（带主键）
     */
    User save(User user);

    /** 只改密码：换 salt 与 hash，不动其它字段 */
    void updatePassword(long id, String hash, String salt);

    /** 记录最近登录时间 */
    void touchLogin(long id, String time);

    /** 分页查询；keyword 为空或空白时不过滤 */
    PageResult<User> page(int page, int size, String keyword);

    /** 该仓储是否真的落盘（两种模式下都是 true，接口与其它仓储保持一致） */
    boolean persistent();
}
