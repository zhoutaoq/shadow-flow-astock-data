package io.github.zhoutaoq.shadowflow.biz.auth;

import io.github.zhoutaoq.shadowflow.biz.auth.mail.EmailCodeService;
import io.github.zhoutaoq.shadowflow.biz.auth.model.GitHubProfile;
import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.auth.model.UserView;
import io.github.zhoutaoq.shadowflow.biz.auth.repo.UserRepository;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 * （即 classpath app.env 的 {@code ADMIN_PASSWORD}，v1.6.1 起没有默认值，真实值在 app.env）。
 * 账号已存在时不覆盖密码，否则页面改完一重启就回到初始值；
 * 口令为空则本轮不建账号并打一条 WARN（见 {@link #ensureAdmin()}）。
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
    private final EmailCodeService emailCodes;

    public AuthService(UserRepository users, AuthProperties props, SessionService sessions,
                       RegistrationNotifier registrationNotifier, EmailCodeService emailCodes) {
        this.users = users;
        this.props = props;
        this.sessions = sessions;
        this.registrationNotifier = registrationNotifier;
        this.emailCodes = emailCodes;
    }

    /** 首次启动时把超管账号建出来 */
    @PostConstruct
    public void ensureAdmin() {
        String name = props.getAdmin().getUsername();
        if (users.findByUsername(name) != null) {
            return;
        }
        // v1.6.1 起口令没有默认值（源码与 application.yml 里都只留 ADMIN_PASSWORD 占位符），
        // 空着就说明 classpath 的 app.env 里没填 ADMIN_PASSWORD。这时刻意不建账号 ——
        // 否则会留下一个「口令是空串、怎么都登不上」的 admin，比没有账号更难排查。
        String initial = props.getAdmin().getPassword();
        if (initial == null || initial.trim().isEmpty()) {
            log.warn("[auth] 超管账号 {} 尚未创建，但 ADMIN_PASSWORD 为空，本轮跳过初始化。"
                    + "口令不写进仓库也不设默认值，请在 src/main/resources/app.env（不入库）里设好 ADMIN_PASSWORD 后重新打包",
                    name);
            return;
        }
        User admin = new User();
        admin.setUsername(name);
        admin.setRole(User.ROLE_ADMIN);
        admin.setEnabled(true);
        String salt = PasswordHasher.newSalt();
        admin.setPasswordSalt(salt);
        admin.setPasswordHash(PasswordHasher.hash(initial, salt));
        users.save(admin);
        log.info("[auth] 已初始化超管账号 {}（初始密码来自 ADMIN_PASSWORD 环境变量）", name);
    }

    /**
     * 注册。
     *
     * <p>v1.7.4 起<b>必须带邮箱验证码</b>：先证明这个邮箱能收信，再开号。
     * 校验顺序刻意把「格式 → 口令合规 → 是否已注册」放在验证码之前 ——
     * 前三条出错时不该白白消耗掉用户手里的那条码。
     *
     * @param username 邮箱
     * @param password 明文口令
     * @param code     邮箱验证码（注册场景）
     * @return 新建的用户
     */
    public User register(String username, String password, String code) {
        if (!props.getAuth().isRegistrationEnabled()) {
            throw new IllegalArgumentException("注册入口已关闭，请联系管理员开通账号");
        }
        String name = checkUsername(username);
        checkPassword(password);
        if (users.findByUsername(name) != null) {
            throw new IllegalArgumentException("该邮箱已注册");
        }
        if (emailCodes.requireOnRegister()) {
            // 没有发信能力就不能放人注册 —— 放进来等于「随便填个邮箱就能开号」，
            // 正是这次要堵的口子。报清楚原因，别让用户对着「验证码已失效」发懵。
            if (!emailCodes.configured()) {
                throw new IllegalArgumentException("注册需要邮箱验证码，但邮件服务尚未配置，请联系管理员");
            }
            emailCodes.verify(name, EmailCodeService.SCENE_REGISTER, code);
        }
        User u = new User();
        u.setUsername(name);
        u.setRole(User.ROLE_USER);
        u.setEnabled(true);
        String salt = PasswordHasher.newSalt();
        u.setPasswordSalt(salt);
        u.setPasswordHash(PasswordHasher.hash(password, salt));
        users.save(u);
        u = reload(u);
        log.info("[auth] 新用户注册 {}", name);
        // 注册埋点：推一条钉钉（注册时间 + 邮箱）。异步 + 内部吞异常，
        // 推送出任何问题都不该让「注册」这件事失败 —— 用户这时候已经建好了
        registrationNotifier.notifyRegistered(u);
        return u;
    }

    /**
     * 登录。
     *
     * <p>v1.7.4 起邮箱账号还要过一次验证码（密码仍是主要凭证，验证码是第二道）：
     * <ul>
     *   <li>先验账号与口令 —— 避免「密码本来就错」的人白白消耗一条验证码；</li>
     *   <li>再验验证码。<b>admin 这类非邮箱用户名豁免</b>：它没有可收信的邮箱，
     *       强制验证码等于把超管锁在门外；</li>
     *   <li>GitHub 三方登录不走这里（OAuth 已经证明了邮箱归属）。</li>
     * </ul>
     *
     * @param username 账号（邮箱）
     * @param password 明文口令
     * @param code     邮箱验证码（登录场景；非邮箱账号可不填）
     * @return 登录态（含令牌）
     */
    public SessionService.Session login(String username, String password, String code) {
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
        if (emailCodeRequired(u)) {
            emailCodes.verify(u.getUsername(), EmailCodeService.SCENE_LOGIN, code);
        }
        String now = LocalDateTime.now().format(DF);
        users.touchLogin(u.getId(), now);
        SessionService.Session s = sessions.create(u);
        log.info("[auth] {} 登录成功（{}）", u.getUsername(), u.isAdmin() ? "超管" : "普通用户");
        return s;
    }

    /**
     * 这个账号登录时要不要验证码。
     *
     * <p>判据是「用户名是邮箱」而不是「角色是普通用户」：三方登录建的号同样是邮箱用户名，
     * 也该走验证码；反过来，以后若出现别的非邮箱账号（运维号、机器人号），
     * 一律不受验证码约束，免得再造出一个「没有邮箱却要求收信」的死账号。
     */
    public boolean emailCodeRequired(User u) {
        return u != null && emailCodes.requireOnLogin() && EMAIL.matcher(u.getUsername()).matches();
    }

    /**
     * 下发验证码。
     *
     * <p>两个场景的区别：
     * <ul>
     *   <li>{@code register} —— 邮箱已注册时直接拒绝（注册本来就会告诉他「已注册」，
     *       这里不必藏），省一封无用的信；</li>
     *   <li>{@code login} —— 账号不存在时<b>不告诉调用方</b>，也不真的发信，一律回同一句
     *       「验证码已发送」。否则这个接口就成了「查某邮箱有没有注册过」的探测工具。</li>
     * </ul>
     *
     * @return 给页面显示的提示
     */
    public String sendEmailCode(String username, String scene) {
        String name = checkUsername(username);
        boolean login = EmailCodeService.SCENE_LOGIN.equals(scene);
        if (!login && !props.getAuth().isRegistrationEnabled()) {
            throw new IllegalArgumentException("注册入口已关闭，请联系管理员开通账号");
        }
        User u = users.findByUsername(name);
        if (!login && u != null) {
            throw new IllegalArgumentException("该邮箱已注册，直接登录即可");
        }
        if (login) {
            if (!emailCodes.requireOnLogin()) {
                throw new IllegalArgumentException("当前登录不需要验证码");
            }
            if (u == null || !u.isEnabled()) {
                // 账号不存在 / 已停用：不发信，但对外与「已发送」完全一致（防探测）
                log.info("[auth] 登录验证码请求：{} 不存在或已停用，按已发送处理（不发信）", name);
                return "验证码已发送至 " + EmailCodeService.maskEmail(name) + "，"
                        + emailCodes.ttlMinutes() + " 分钟内有效";
            }
        } else if (!emailCodes.requireOnRegister()) {
            throw new IllegalArgumentException("当前注册不需要验证码");
        }
        return emailCodes.send(name, login ? EmailCodeService.SCENE_LOGIN : EmailCodeService.SCENE_REGISTER);
    }

    /** 退出登录 */
    public void logout(String token) {
        sessions.remove(token);
    }

    /**
     * GitHub 三方登录：按 GitHub 身份找到或建出系统用户，签发登录态。
     *
     * <p>匹配顺序（先绑后建，绝不覆盖已有口令账号）：
     * <ol>
     *   <li>按 {@code github_id} 找 —— 绑定过的直接进；</li>
     *   <li>按邮箱找 —— 邮箱注册过的人点 GitHub 登录就是「补绑」，
     *       把 GitHub 身份挂到老账号上，历史数据一点不丢；</li>
     *   <li>都没有才建新号 —— 新号沿用注册规则（注册开关 / 邮箱用户名），
     *       口令是随机生成的<strong>不可用口令</strong>：三方登录的用户不该有「一个大家都猜不到但确实存在」的口令，
     *       他要设口令得走改密（先由超管重置）。</li>
     * </ol>
     *
     * <p>用户被停用时拒绝登录 —— 与口令登录同一口径，超管停号就是要把他挡在门外。
     */
    public SessionService.Session loginByGithub(GitHubProfile profile) {
        if (profile == null || profile.getId() == null || profile.getId().trim().isEmpty()) {
            throw new IllegalArgumentException("GitHub 账号信息不完整，请重新登录");
        }
        String gid = profile.getId().trim();
        String glogin = profile.getLogin() == null ? "" : profile.getLogin().trim();

        User u = users.findByGithubId(gid);
        if (u == null) {
            // 没绑过：邮箱撞上已有账号就补绑，否则建新号
            String email = profile.resolveUsername();
            User byMail = users.findByUsername(email);
            if (byMail != null) {
                users.bindGithub(byMail.getId(), gid, glogin);
                log.info("[auth] {} 补绑 GitHub 账号 {}（{}）", byMail.getUsername(), glogin, gid);
                u = byMail;
            } else {
                u = registerByGithub(profile, email);
            }
        } else if (!u.isEnabled()) {
            throw new IllegalArgumentException("账号已停用，请联系管理员");
        }
        if (!u.isEnabled()) {
            throw new IllegalArgumentException("账号已停用，请联系管理员");
        }
        // GitHub 登录名可能改过，顺手刷新展示值（绑定关系以 id 为准，不动）
        if (!glogin.isEmpty() && !glogin.equals(u.getGithubLogin())) {
            users.bindGithub(u.getId(), gid, glogin);
        }
        String now = LocalDateTime.now().format(DF);
        users.touchLogin(u.getId(), now);
        SessionService.Session s = sessions.create(u);
        log.info("[auth] {} 通过 GitHub 登录成功（{}）", u.getUsername(), glogin);
        return s;
    }

    /** 三方登录首次建号：走与邮箱注册一样的开关与用户名规则，口令随机（只走 GitHub） */
    private User registerByGithub(GitHubProfile profile, String email) {
        if (!props.getAuth().isRegistrationEnabled()) {
            throw new IllegalArgumentException("注册入口已关闭，且该 GitHub 账号尚未绑定系统账号，请联系管理员");
        }
        String name = checkUsername(email);
        if (users.findByUsername(name) != null) {
            // 并发下两边同时注册，后到的走补绑而不是建号
            User exist = users.findByUsername(name);
            users.bindGithub(exist.getId(), profile.getId().trim(),
                    profile.getLogin() == null ? "" : profile.getLogin().trim());
            return exist;
        }
        User nu = new User();
        nu.setUsername(name);
        nu.setRole(User.ROLE_USER);
        nu.setEnabled(true);
        String salt = PasswordHasher.newSalt();
        nu.setPasswordSalt(salt);
        // 随机不可用口令：三方登录用户不设口令，避免留下「没人知道但能爆破」的口令面
        nu.setPasswordHash(PasswordHasher.hash(UUID.randomUUID().toString(), salt));
        nu.setGithubId(profile.getId().trim());
        nu.setGithubLogin(profile.getLogin() == null ? "" : profile.getLogin().trim());
        users.save(nu);
        nu = reload(nu);
        log.info("[auth] 新用户经 GitHub 注册 {}（GitHub: {}）", name, nu.getGithubLogin());
        registrationNotifier.notifyRegistered(nu);
        return nu;
    }

    /**
     * 重新取一次刚写入的用户。
     *
     * <p>仓储的 {@code save()} <b>不保证把新分配的 id 回填到入参对象</b>（文件模式是新建一份
     * User 存进内存，入参那个还是 id=null）。注册完立刻要拿 id 签发登录态，
     * 不重取就会得到「用户不存在」—— 账号其实已经建好了，用户却看到失败。
     */
    private User reload(User u) {
        if (u == null) {
            return null;
        }
        User fresh = users.findByUsername(u.getUsername());
        return fresh == null ? u : fresh;
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

    /**
     * 注册成功后直接签发登录态。
     *
     * <p>注册这条路本来就已经用验证码证明了「邮箱是本人的」，再让用户为了登录
     * 收第二条验证码纯属折腾；而且注册用的那条码在校验通过时已作废，
     * 想「拿刚收到的码顺手登录」也做不到。所以注册成功即登录，前端拿到令牌直接进站。
     */
    public SessionService.Session openSessionAfterRegister(User u) {
        if (u == null || u.getId() == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        users.touchLogin(u.getId(), LocalDateTime.now().format(DF));
        return sessions.create(u);
    }

    /** 邮箱验证码规则（前端登录弹窗据此决定显不显示、多久能重发） */
    public java.util.Map<String, Object> emailCodeRules() {
        return emailCodes.rules();
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
