package io.github.zhoutaoq.shadowflow.biz.auth.model;

/**
 * 系统用户（SaaS 化的最小账户模型）。
 *
 * <p>用户名固定为邮箱，注册时校验格式与唯一性；密码永不明文落盘，
 * 只存「随机 salt + SHA-256(salt:明文)」的哈希，见 {@code PasswordHasher}。
 *
 * <p>v1.3 只有两个角色：
 * <ul>
 *   <li>{@link #ROLE_ADMIN} 超管，用户名固定 {@code admin}，初始密码由启动环境变量
 *       {@code ADMIN_PASSWORD} 决定（默认 {@code 1}）；只有它能进「用户管理」；</li>
 *   <li>{@link #ROLE_USER} 注册用户，只能用回测、盯盘这些登录后才开放的功能。</li>
 * </ul>
 */
public class User {

    /** 超管角色 */
    public static final String ROLE_ADMIN = "admin";
    /** 普通注册用户 */
    public static final String ROLE_USER = "user";

    private Long id;
    private String username;
    /** 密码哈希（SHA-256 hex），不回传给前端 */
    private String passwordHash;
    /** 密码盐（hex），每个用户独立随机生成 */
    private String passwordSalt;
    /**
     * GitHub 用户 id（OAuth 绑定后非空）。
     *
     * <p>用字符串而不是数字：GitHub id 是纯数字但位数无承诺，字符串免溢出之忧；
     * 它是「这个系统用户对应哪个 GitHub 账号」的唯一判据，登录时先按它找人。
     */
    private String githubId;
    /** GitHub 登录名，纯展示用；改名后下次 OAuth 登录会刷新 */
    private String githubLogin;
    private String role = ROLE_USER;
    private boolean enabled = true;
    private String createTime;
    private String updateTime;
    private String lastLoginTime;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getPasswordSalt() {
        return passwordSalt;
    }

    public void setPasswordSalt(String passwordSalt) {
        this.passwordSalt = passwordSalt;
    }

    public String getGithubId() {
        return githubId;
    }

    public void setGithubId(String githubId) {
        this.githubId = githubId;
    }

    public String getGithubLogin() {
        return githubLogin;
    }

    public void setGithubLogin(String githubLogin) {
        this.githubLogin = githubLogin;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAdmin() {
        return ROLE_ADMIN.equals(role);
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public String getLastLoginTime() {
        return lastLoginTime;
    }

    public void setLastLoginTime(String lastLoginTime) {
        this.lastLoginTime = lastLoginTime;
    }
}
