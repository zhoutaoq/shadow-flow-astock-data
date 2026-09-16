package com.stock.kline.auth;

import com.stock.kline.auth.model.User;
import com.stock.kline.auth.model.UserView;
import com.stock.kline.auth.repo.UserRepository;
import com.stock.kline.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 账号的全部业务规则：注册、登录、改密、用户分页。
 *
 * <p>校验集中在 {@link #checkUsername} / {@link #checkPassword}，注册与改密共用，
 * 免得两条路径的规则哪天走偏：
 * <ul>
 *   <li>用户名必须是合法邮箱，且全库唯一；</li>
 *   <li>密码不少于 6 位，且必须同时包含字母和数字。</li>
 * </ul>
 *
 * <p>超管 {@code admin} 在首次启动时自动创建，初始密码取 {@code app.admin.password}
 * （由启动环境变量 {@code ADMIN_PASSWORD} 覆盖）。账号已存在时不覆盖密码，
 * 否则页面改完一重启就回到初始值。
 *
 * <p>注册成功后会经 {@link RegistrationNotifier} 推一条钉钉埋点（注册时间 + 邮箱）。
 * 那是「顺手通知」而不是注册流程的一环：异步发送、推不出去也只写日志，注册照常成功。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 邮箱格式：本地部分 + @ + 至少一个点分隔的域名 */
    private static final Pattern EMAIL =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

    /** 密码最短长度 */
    public static final int MIN_PASSWORD_LENGTH = 6;

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final UserRepository users;
    private final AuthProperties props;
    private final SessionService sessions;
    private final RegistrationNotifier registrationNotifier;

    public AuthService(UserRepository users, AuthProperties props, SessionService sessions,
                       RegistrationNotifier registrationNotifier) {
        this.users = users;
        this.props = props;
        this.sessions = sessions;
        this.registrationNotifier = registrationNotifier;
    }

    /** 首次启动时把超管账号建出来 */
    @PostConstruct
    public void ensureAdmin() {
        String name = props.getAdmin().getUsername();
        if (users.findByUsername(name) != null) {
            return;
        }
        User admin = new User();
        admin.setUsername(name);
        admin.setRole(User.ROLE_ADMIN);
        admin.setEnabled(true);
        String salt = PasswordHasher.newSalt();
        admin.setPasswordSalt(salt);
        admin.setPasswordHash(PasswordHasher.hash(props.getAdmin().getPassword(), salt));
        users.save(admin);
        log.info("[auth] 已初始化超管账号 {}（初始密码来自 ADMIN_PASSWORD 环境变量，未设置时为默认值）", name);
    }

    /**
     * 注册。
     *
     * @param username 邮箱
     * @param password 明文口令
     * @return 新建的用户
     */
    public User register(String username, String password) {
        if (!props.getAuth().isRegistrationEnabled()) {
            throw new IllegalArgumentException("注册入口已关闭，请联系管理员开通账号");
        }
        String name = checkUsername(username);
        checkPassword(password);
        if (users.findByUsername(name) != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        User u = new User();
        u.setUsername(name);
        u.setRole(User.ROLE_USER);
        u.setEnabled(true);
        String salt = PasswordHasher.newSalt();
        u.setPasswordSalt(salt);
        u.setPasswordHash(PasswordHasher.hash(password, salt));
        users.save(u);
        log.info("[auth] 新用户注册 {}", name);
        // 注册埋点：推一条钉钉（注册时间 + 邮箱）。异步 + 内部吞异常，
        // 推送出任何问题都不该让「注册」这件事失败 —— 用户这时候已经建好了
        registrationNotifier.notifyRegistered(u);
        return u;
    }

    /**
     * 登录。
     *
     * @return 登录态（含令牌）
     */
    public SessionService.Session login(String username, String password) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty() || password == null || password.isEmpty()) {
            throw new IllegalArgumentException("用户名与密码都不能为空");
        }
        User u = users.findByUsername(name);
        // 用户不存在与密码错误返回同一句话，避免被用来探测哪些邮箱注册过
        if (u == null || !u.isEnabled()
                || !PasswordHasher.matches(password, u.getPasswordSalt(), u.getPasswordHash())) {
            throw new IllegalArgumentException("用户名或密码不正确");
        }
        String now = LocalDateTime.now().format(DF);
        users.touchLogin(u.getId(), now);
        SessionService.Session s = sessions.create(u);
        log.info("[auth] {} 登录成功（{}）", u.getUsername(), u.isAdmin() ? "超管" : "普通用户");
        return s;
    }

    /** 退出登录 */
    public void logout(String token) {
        sessions.remove(token);
    }

    /** 本人改密：必须验证旧密码 */
    public void changePassword(long userId, String oldPassword, String newPassword) {
        User u = users.findById(userId);
        if (u == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        if (oldPassword == null || oldPassword.isEmpty()) {
            throw new IllegalArgumentException("请输入原密码");
        }
        if (!PasswordHasher.matches(oldPassword, u.getPasswordSalt(), u.getPasswordHash())) {
            throw new IllegalArgumentException("原密码不正确");
        }
        checkPassword(newPassword);
        resetPassword(userId, newPassword);
        log.info("[auth] {} 已修改自己的密码", u.getUsername());
    }

    /**
     * 重置密码（超管改任何人、或本人改密走完校验后调这里）。
     *
     * <p>不校验旧密码 —— 超管重置别人密码时不可能知道对方的旧口令。
     */
    public void resetPassword(long userId, String newPassword) {
        checkPassword(newPassword);
        User u = users.findById(userId);
        if (u == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        String salt = PasswordHasher.newSalt();
        users.updatePassword(userId, PasswordHasher.hash(newPassword, salt), salt);
        // 改密后把该用户的所有登录态作废，逼他重新登录一次
        int killed = sessions.removeByUserId(userId);
        log.info("[auth] {} 的密码已被重置，已作废 {} 个登录态", u.getUsername(), killed);
    }

    /** 用户分页（外发视图，不含密码哈希） */
    public PageResult<UserView> userPage(int page, int size, String keyword) {
        PageResult<User> raw = users.page(page, size, keyword);
        List<UserView> views = new ArrayList<>();
        if (raw.getRows() != null) {
            for (User u : raw.getRows()) {
                views.add(UserView.of(u));
            }
        }
        return new PageResult<>(views, raw.getTotal(), raw.getPage(), raw.getSize());
    }

    /** 用户名校验：必须是邮箱且未被占用 */
    public String checkUsername(String username) {
        String name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("用户名不能为空");
        }
        if (!EMAIL.matcher(name).matches()) {
            throw new IllegalArgumentException("用户名必须是有效的邮箱地址");
        }
        if (name.length() > 128) {
            throw new IllegalArgumentException("邮箱地址过长");
        }
        return name;
    }

    /**
     * 密码校验：不少于 6 位，且同时含字母与数字。
     *
     * @throws IllegalArgumentException 不满足时带上具体原因
     */
    public static void checkPassword(String password) {
        if (password == null || password.trim().isEmpty()) {
            throw new IllegalArgumentException("密码不能为空");
        }
        String p = password.trim();
        if (p.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("密码至少 " + MIN_PASSWORD_LENGTH + " 位");
        }
        boolean hasLetter = false, hasDigit = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                hasLetter = true;
            } else if (c >= '0' && c <= '9') {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw new IllegalArgumentException("密码必须同时包含字母和数字");
        }
    }

    /** 当前生效的注册/密码规则，页面据此给出一致的提示 */
    public static java.util.Map<String, Object> rules() {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("minPasswordLength", MIN_PASSWORD_LENGTH);
        m.put("requireLetterAndDigit", true);
        m.put("usernamePattern", "email");
        return m;
    }

    /** 是否开放注册 */
    public boolean registrationEnabled() {
        return props.getAuth().isRegistrationEnabled();
    }

    /** 超管用户名 */
    public String adminUsername() {
        return StringUtils.hasText(props.getAdmin().getUsername())
                ? props.getAdmin().getUsername() : "admin";
    }

    /**
     * 超管用户 id；账号还没建出来（理论上不会，ensureAdmin 已经跑过）返回 0。
     *
     * <p>盯盘配置靠它定位「系统默认那份」归谁。
     */
    public long adminId() {
        User u = users.findByUsername(adminUsername());
        return u == null || u.getId() == null ? 0L : u.getId();
    }
}
