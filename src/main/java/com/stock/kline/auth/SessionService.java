package com.stock.kline.auth;

import com.stock.kline.auth.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * 登录态：令牌 → 用户。
 *
 * <p>只放在内存里，进程重启即失效（用户要重新登录一次）。
 * 没有引入 Spring Security，也不打算为单机 SaaS 雏形引入会话表，
 * 内存态够用且不会因为落盘把令牌泄到数据文件里。
 *
 * <p>过期令牌不会主动清除，而是「查到才发现过期再删」；
 * 顺带在每次创建令牌时按 1% 的概率扫一遍，避免长期运行堆一堆垃圾。
 */
@Component
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final ConcurrentMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final long ttlMillis;

    public SessionService(AuthProperties props) {
        this.ttlMillis = TimeUnit.HOURS.toMillis(Math.max(1, props.getAuth().getSessionHours()));
        log.info("[auth] 登录态有效时长 {} 小时", props.getAuth().getSessionHours());
    }

    /** 签发一个令牌；同一个用户可以有多个令牌（多浏览器同时登录） */
    public Session create(User user) {
        String token = UUID.randomUUID().toString().replace("-", "");
        Session s = new Session(token, user.getId(), user.getUsername(),
                user.getRole(), System.currentTimeMillis() + ttlMillis);
        sessions.put(token, s);
        if (sessions.size() > 64 && (sessions.size() & 63) == 0) {
            sweep();
        }
        return s;
    }

    /** 查令牌；过期或不存在返回 null */
    public Session get(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        Session s = sessions.get(token.trim());
        if (s == null) {
            return null;
        }
        if (s.isExpired()) {
            sessions.remove(token.trim());
            return null;
        }
        return s;
    }

    /** 登出：令牌立即作废 */
    public void remove(String token) {
        if (token != null && !token.trim().isEmpty()) {
            sessions.remove(token.trim());
        }
    }

    /**
     * 作废某个用户的全部登录态。
     *
     * <p>改密码后调它：不然别人改了你的口令，你手上这个已经登录的浏览器还能继续用，
     * 等于改了个寂寞。
     */
    public int removeByUserId(long userId) {
        int n = 0;
        for (Map.Entry<String, Session> e : sessions.entrySet()) {
            if (e.getValue().getUserId() == userId) {
                sessions.remove(e.getKey());
                n++;
            }
        }
        return n;
    }

    /** 清掉已过期的令牌 */
    public void sweep() {
        int before = sessions.size();
        Iterator<Map.Entry<String, Session>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
            }
        }
        int removed = before - sessions.size();
        if (removed > 0) {
            log.debug("[auth] 已清理 {} 个过期登录态", removed);
        }
    }

    /** 一个登录态 */
    public static class Session {

        private final String token;
        private final long userId;
        private final String username;
        private final String role;
        private final long expireAt;

        Session(String token, long userId, String username, String role, long expireAt) {
            this.token = token;
            this.userId = userId;
            this.username = username;
            this.role = role;
            this.expireAt = expireAt;
        }

        public String getToken() {
            return token;
        }

        public long getUserId() {
            return userId;
        }

        public String getUsername() {
            return username;
        }

        public String getRole() {
            return role;
        }

        public long getExpireAt() {
            return expireAt;
        }

        public boolean isAdmin() {
            return User.ROLE_ADMIN.equals(role);
        }

        public boolean isExpired() {
            return System.currentTimeMillis() > expireAt;
        }

        /** 剩余有效秒数 */
        public long expiresInSeconds() {
            long left = expireAt - System.currentTimeMillis();
            return left > 0 ? left / 1000 : 0;
        }
    }
}
