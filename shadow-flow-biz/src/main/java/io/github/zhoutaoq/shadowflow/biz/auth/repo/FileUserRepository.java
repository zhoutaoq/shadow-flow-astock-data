package io.github.zhoutaoq.shadowflow.biz.auth.repo;

import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import io.github.zhoutaoq.shadowflow.biz.store.FileTable;
import io.github.zhoutaoq.shadowflow.biz.store.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 无数据库模式：用户落 {@code ./data/users.json}，启动时整表载入内存。
 *
 * <p>用户名是邮箱，内存里按小写用户名建索引，保证「注册唯一性」的判定
 * 与数据库模式一致（MySQL 默认校对规则也不区分大小写）。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileUserRepository implements UserRepository {

    private static final Logger log = LoggerFactory.getLogger(FileUserRepository.class);

    private static final String FILE = "users.json";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final FileTable<User> table;

    /** 小写用户名 → 用户行 */
    private volatile Map<String, User> rows = new LinkedHashMap<>();
    private volatile long maxId = 0;

    public FileUserRepository(DataDir dataDir, StoreProperties storeProps) {
        this.table = new FileTable<>(dataDir, FILE, User.class, storeProps.getMaxRows());
    }

    @PostConstruct
    public synchronized void init() {
        reload();
        log.info("[auth] 无数据库模式：{} 已载入 {} 个用户", table.file(), rows.size());
    }

    @Override
    public synchronized User findByUsername(String username) {
        if (!StringUtils.hasText(username)) {
            return null;
        }
        User u = rows.get(username.trim().toLowerCase());
        return u == null ? null : copy(u);
    }

    @Override
    public synchronized User findById(long id) {
        for (User u : rows.values()) {
            if (u.getId() != null && u.getId() == id) {
                return copy(u);
            }
        }
        return null;
    }

    @Override
    public synchronized User save(User user) {
        if (user == null) {
            throw new IllegalArgumentException("用户不能为空");
        }
        String name = normalize(user.getUsername());
        if (name.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        String now = LocalDateTime.now().format(DF);
        Map<String, User> fresh = new LinkedHashMap<>(rows);
        User exist = fresh.get(name.toLowerCase());
        if (exist == null) {
            exist = new User();
            exist.setId(++maxId);
            exist.setUsername(name);
            exist.setRole(user.getRole() == null ? User.ROLE_USER : user.getRole());
            exist.setEnabled(true);
            exist.setCreateTime(now);
        }
        if (StringUtils.hasText(user.getPasswordHash())) {
            exist.setPasswordHash(user.getPasswordHash());
            exist.setPasswordSalt(user.getPasswordSalt());
        }
        if (StringUtils.hasText(user.getRole())) {
            exist.setRole(user.getRole());
        }
        exist.setEnabled(user.isEnabled());
        exist.setUpdateTime(now);
        fresh.put(name.toLowerCase(), exist);
        rows = fresh;
        persist();
        return copy(exist);
    }

    @Override
    public synchronized void updatePassword(long id, String hash, String salt) {
        String now = LocalDateTime.now().format(DF);
        Map<String, User> fresh = new LinkedHashMap<>(rows);
        for (Map.Entry<String, User> e : fresh.entrySet()) {
            User u = e.getValue();
            if (u.getId() != null && u.getId() == id) {
                u.setPasswordHash(hash);
                u.setPasswordSalt(salt);
                u.setUpdateTime(now);
            }
        }
        rows = fresh;
        persist();
    }

    @Override
    public synchronized void touchLogin(long id, String time) {
        Map<String, User> fresh = new LinkedHashMap<>(rows);
        for (Map.Entry<String, User> e : fresh.entrySet()) {
            User u = e.getValue();
            if (u.getId() != null && u.getId() == id) {
                u.setLastLoginTime(time);
            }
        }
        rows = fresh;
        persist();
    }

    @Override
    public synchronized PageResult<User> page(int page, int size, String keyword) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : size;
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<User> all = new ArrayList<>(rows.values());
        all.sort(Comparator.comparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        List<User> matched = new ArrayList<>();
        for (User u : all) {
            if (kw.isEmpty() || (u.getUsername() != null && u.getUsername().toLowerCase().contains(kw))) {
                matched.add(copy(u));
            }
        }
        int total = matched.size();
        int from = Math.min((p - 1) * s, total);
        int to = Math.min(from + s, total);
        return new PageResult<>(matched.subList(from, to), total, p, s);
    }

    @Override
    public boolean persistent() {
        return true;
    }

    /** 从文件重新载入 */
    public synchronized void reload() {
        Map<String, User> fresh = new LinkedHashMap<>();
        long max = 0;
        for (User u : table.load()) {
            if (u == null || u.getUsername() == null || u.getUsername().trim().isEmpty()) {
                continue;
            }
            if (u.getId() != null && u.getId() > max) {
                max = u.getId();
            }
            if (u.getRole() == null || u.getRole().trim().isEmpty()) {
                u.setRole(User.ROLE_USER);
            }
            fresh.put(u.getUsername().trim().toLowerCase(), u);
        }
        maxId = max;
        rows = fresh;
    }

    private void persist() {
        List<User> list = new ArrayList<>(rows.values());
        list.sort(Comparator.comparing(User::getId, Comparator.nullsLast(Comparator.naturalOrder())));
        table.save(list);
    }

    private static User copy(User u) {
        User c = new User();
        c.setId(u.getId());
        c.setUsername(u.getUsername());
        c.setPasswordHash(u.getPasswordHash());
        c.setPasswordSalt(u.getPasswordSalt());
        c.setRole(u.getRole());
        c.setEnabled(u.isEnabled());
        c.setCreateTime(u.getCreateTime());
        c.setUpdateTime(u.getUpdateTime());
        c.setLastLoginTime(u.getLastLoginTime());
        return c;
    }

    private static String normalize(String username) {
        return username == null ? "" : username.trim();
    }
}
