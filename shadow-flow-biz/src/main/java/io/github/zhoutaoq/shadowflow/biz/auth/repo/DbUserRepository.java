package io.github.zhoutaoq.shadowflow.biz.auth.repo;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据库模式：用户落 {@code waizao_user}。
 *
 * <p>表由 schema.sql 建（启动即执行）。用户名上有唯一索引，
 * 注册时的「唯一性」最终由它兜底 —— 并发下两个请求同时查重通过，
 * 也会有一个被唯一索引挡在门外。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(DbUserRepository.class);

    private static final String TABLE = "waizao_user";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public DbUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        log.info("[auth] 数据库模式：{} 现有 {} 个用户", TABLE, n);
    }

    @Override
    public User findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        List<User> list = jdbcTemplate.query(
                "SELECT id, username, password_hash, password_salt, role, enabled,"
                        + " create_time, update_time, last_login_time FROM " + TABLE
                        + " WHERE username = ? LIMIT 1",
                new Object[]{username.trim()}, this::map);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public User findById(long id) {
        List<User> list = jdbcTemplate.query(
                "SELECT id, username, password_hash, password_salt, role, enabled,"
                        + " create_time, update_time, last_login_time FROM " + TABLE
                        + " WHERE id = ? LIMIT 1",
                new Object[]{id}, this::map);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        if (!StringUtils.hasText(user.getUsername())) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String name = user.getUsername().trim();
        String role = StringUtils.hasText(user.getRole()) ? user.getRole() : User.ROLE_USER;
        String now = LocalDateTime.now().format(DF);

        if (user.getId() == null) {
            KeyHolder holder = new GeneratedKeyHolder();
            final String insert = "INSERT INTO " + TABLE
                    + " (username, password_hash, password_salt, role, enabled, create_time, update_time)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, user.getPasswordHash());
                ps.setString(3, user.getPasswordSalt());
                ps.setString(4, role);
                ps.setInt(5, user.isEnabled() ? 1 : 0);
                ps.setString(6, now);
                ps.setString(7, now);
                return ps;
            }, holder);
            Number key = holder.getKey();
            user.setId(key == null ? null : key.longValue());
            user.setRole(role);
            user.setCreateTime(now);
            user.setUpdateTime(now);
            return user;
        }

        jdbcTemplate.update("UPDATE " + TABLE
                        + " SET username = ?, role = ?, enabled = ?, update_time = ? WHERE id = ?",
                name, role, user.isEnabled() ? 1 : 0, now, user.getId());
        user.setUpdateTime(now);
        return user;
    }

    @Override
    public void updatePassword(long id, String hash, String salt) {
        jdbcTemplate.update("UPDATE " + TABLE
                        + " SET password_hash = ?, password_salt = ?, update_time = ? WHERE id = ?",
                hash, salt, LocalDateTime.now().format(DF), id);
    }

    @Override
    public void touchLogin(long id, String time) {
        jdbcTemplate.update("UPDATE " + TABLE + " SET last_login_time = ? WHERE id = ?", time, id);
    }

    @Override
    public PageResult<User> page(int page, int size, String keyword) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : size;
        String kw = keyword == null ? "" : keyword.trim();
        boolean filtered = !kw.isEmpty();
        String where = filtered ? " WHERE username LIKE ?" : "";
        String like = "%" + kw + "%";

        long total;
        if (filtered) {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TABLE + where,
                    new Object[]{like}, Long.class);
            total = n == null ? 0 : n;
        } else {
            Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
            total = n == null ? 0 : n;
        }

        List<User> rows;
        String sql = "SELECT id, username, password_hash, password_salt, role, enabled,"
                + " create_time, update_time, last_login_time FROM " + TABLE + where
                + " ORDER BY id ASC LIMIT ? OFFSET ?";
        if (filtered) {
            rows = jdbcTemplate.query(sql, new Object[]{like, s, (p - 1) * s}, this::map);
        } else {
            rows = jdbcTemplate.query(sql, new Object[]{s, (p - 1) * s}, this::map);
        }
        return new PageResult<>(rows == null ? new ArrayList<>() : rows, total, p, s);
    }

    @Override
    public boolean persistent() {
        return true;
    }

    private User map(ResultSet rs, int rowNum) throws SQLException {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setPasswordSalt(rs.getString("password_salt"));
        u.setRole(rs.getString("role"));
        u.setEnabled(rs.getInt("enabled") == 1);
        u.setCreateTime(time(rs.getTimestamp("create_time")));
        u.setUpdateTime(time(rs.getTimestamp("update_time")));
        u.setLastLoginTime(time(rs.getTimestamp("last_login_time")));
        return u;
    }

    private static String time(java.sql.Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime().format(DF);
    }
}
