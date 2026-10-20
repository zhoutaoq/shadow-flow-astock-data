package io.github.zhoutaoq.shadowflow.biz.auth.repo;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.context.annotation.DependsOn;
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
 * 数据库模式：用户落 {@code shadowflow_user}。
 *
 * <p>表由 schema.sql 建（启动即执行）。用户名上有唯一索引，
 * 注册时的「唯一性」最终由它兜底 —— 并发下两个请求同时查重通过，
 * 也会有一个被唯一索引挡在门外。
 */
@Repository
@DependsOn("shadowFlowDbInitializer")
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
public class DbUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(DbUserRepository.class);

    private static final String TABLE = "shadowflow_user";
    /** 老库升级（init 里的 ALTER）跑完后这两列一定在，统一列名免得各查询写散了 */
    private static final String COLUMNS = "id, username, password_hash, password_salt,"
            + " github_id, github_login, role, enabled,"
            + " create_time, update_time, last_login_time";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public DbUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void init() {
        // 老库补列：github_id / github_login 缺失时加上（v1.6.8 三方登录）。
        // 幂等 —— 新库由 schema.sql 建表时就带着这两列，这里的 ALTER 必然报「列已存在」，
        // 属于正常情况，只记 debug（与 DbBacktestRepository 同一套路）。
        exec("ALTER TABLE " + TABLE + " ADD COLUMN github_id VARCHAR(32) NULL"
                + " COMMENT 'GitHub 用户 id（OAuth 绑定后非空）' AFTER password_salt");
        exec("ALTER TABLE " + TABLE + " ADD COLUMN github_login VARCHAR(64) NULL"
                + " COMMENT 'GitHub 登录名，纯展示' AFTER github_id");
        exec("ALTER TABLE " + TABLE + " ADD UNIQUE KEY uk_shadowflow_user_github (github_id)");
        Long n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + TABLE, Long.class);
        log.info("[auth] 数据库模式：{} 现有 {} 个用户", TABLE, n);
    }

    private void exec(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (Exception e) {
            // 列/索引已存在是常态，不打扰日志
            log.debug("[auth] 迁移跳过：{}", e.getMessage());
        }
    }

    @Override
    public User findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        List<User> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE username = ? LIMIT 1",
                new Object[]{username.trim()}, this::map);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public User findById(long id) {
        List<User> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE id = ? LIMIT 1",
                new Object[]{id}, this::map);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public User findByGithubId(String githubId) {
        if (!StringUtils.hasText(githubId)) {
            return null;
        }
        List<User> list = jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE github_id = ? LIMIT 1",
                new Object[]{githubId.trim()}, this::map);
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
                    + " (username, password_hash, password_salt, github_id, github_login, role, enabled, create_time, update_time)"
                    + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name);
                ps.setString(2, user.getPasswordHash());
                ps.setString(3, user.getPasswordSalt());
                ps.setString(4, user.getGithubId());
                ps.setString(5, user.getGithubLogin());
                ps.setString(6, role);
                ps.setInt(7, user.isEnabled() ? 1 : 0);
                ps.setString(8, now);
                ps.setString(9, now);
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
    public void bindGithub(long id, String githubId, String githubLogin) {
        jdbcTemplate.update("UPDATE " + TABLE
                        + " SET github_id = ?, github_login = ?, update_time = ? WHERE id = ?",
                githubId, githubLogin, LocalDateTime.now().format(DF), id);
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
        String sql = "SELECT " + COLUMNS + " FROM " + TABLE + where
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
        u.setGithubId(rs.getString("github_id"));
        u.setGithubLogin(rs.getString("github_login"));
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
