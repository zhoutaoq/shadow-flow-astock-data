package io.github.zhoutaoq.shadowflow.biz.auth.model;

/**
 * 用户的外发视图：与 {@link User} 的区别只有一点 —— 不带密码哈希与盐。
 *
 * <p>用户管理页、登录结果都要把用户回传给浏览器，直接用实体类会把哈希一起发出去，
 * 这里显式收一道口子。
 */
public class UserView {

    private Long id;
    private String username;
    private String role;
    private boolean enabled;
    private boolean admin;
    private String createTime;
    private String updateTime;
    private String lastLoginTime;

    public static UserView of(User u) {
        if (u == null) {
            return null;
        }
        UserView v = new UserView();
        v.setId(u.getId());
        v.setUsername(u.getUsername());
        v.setRole(u.getRole());
        v.setEnabled(u.isEnabled());
        v.setAdmin(u.isAdmin());
        v.setCreateTime(u.getCreateTime());
        v.setUpdateTime(u.getUpdateTime());
        v.setLastLoginTime(u.getLastLoginTime());
        return v;
    }

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
        return admin;
    }

    public void setAdmin(boolean admin) {
        this.admin = admin;
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
