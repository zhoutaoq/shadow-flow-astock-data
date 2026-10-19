package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthProperties;
import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.auth.repo.UserRepository;
import org.springframework.stereotype.Component;

/**
 * 回测记录的归属规则（v1.6.8 第 ① 条：回测记录按用户隔离）。
 *
 * <p>两种模式都要回答同一个问题：「这条记录能不能给这个人看」。规则只有两条：
 * <ol>
 *   <li>记录带 {@code userId} 且等于当前用户 → 能看；</li>
 *   <li>记录没有 {@code userId}（{@code <= 0}，即 v1.6.8 之前落的）→ <b>只有超管能看</b>。</li>
 * </ol>
 *
 * <p>为什么老记录归超管而不是删掉：升级前的回测全是超管自己跑的（当时回测入口不区分用户），
 * 归给超管既保住了历史，又天然满足「不要跨用户展示」—— 普通用户一条都看不到。
 *
 * <p>超管 id 是<b>懒解析</b>的：仓储初始化（{@code @PostConstruct}）早于账号播种，
 * 那时候还查不到 admin；等真正有人来查列表时账号一定已经建好了。
 */
@Component
public class BacktestOwnership {

    private final UserRepository users;
    private final AuthProperties props;

    /** 超管 id 缓存；0 = 还没解析出来 */
    private volatile long adminId;

    public BacktestOwnership(UserRepository users, AuthProperties props) {
        this.users = users;
        this.props = props;
    }

    /**
     * 这条记录对 {@code userId} 是否可见。
     *
     * @param recordUserId 记录自己的归属（0 = 老记录）
     */
    public boolean visible(long recordUserId, long userId) {
        if (userId <= 0) {
            return false;
        }
        if (recordUserId == userId) {
            return true;
        }
        return recordUserId <= 0 && userId == adminId();
    }

    /** 超管用户 id；账号还没建出来时返回 0（此时老记录对谁都不展示，等账号建好再自然可见） */
    public long adminId() {
        long cached = adminId;
        if (cached > 0) {
            return cached;
        }
        String name = props.getAdmin() == null || props.getAdmin().getUsername() == null
                || props.getAdmin().getUsername().trim().isEmpty()
                ? "admin" : props.getAdmin().getUsername().trim();
        User u = users.findByUsername(name);
        if (u == null || u.getId() <= 0) {
            return 0;
        }
        adminId = u.getId();
        return adminId;
    }
}
