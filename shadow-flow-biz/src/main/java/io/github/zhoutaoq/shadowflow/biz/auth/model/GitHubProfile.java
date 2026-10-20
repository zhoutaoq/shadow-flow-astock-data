package io.github.zhoutaoq.shadowflow.biz.auth.model;

/**
 * GitHub OAuth 拉回来的用户资料（协议层产物，账号编排前的原始信息）。
 *
 * <p>email 取「已验证的主邮箱」——GitHub 上没公开邮箱的人一大把，
 * {@code /user} 的 email 字段经常是 null，得再查 {@code /user/emails} 挑 primary+verified 的。
 * 一个都挑不到就回落到 {@code <id>+<login>@users.noreply.github.com}（GitHub 官方 noreply 形态），
 * 保证系统用户名（必须是邮箱）总能落一个合法值。
 */
public class GitHubProfile {

    /** GitHub 用户 id（数字串，原样当字符串存，免溢出之忧） */
    private String id;
    /** GitHub 登录名 */
    private String login;
    /** 已验证主邮箱（可能为空） */
    private String email;
    /** 昵称（展示用，可能为空） */
    private String name;

    /**
     * 落到系统用户名的邮箱：优先已验证主邮箱，没有就用 GitHub noreply 地址兜底。
     */
    public String resolveUsername() {
        if (email != null && !email.trim().isEmpty()) {
            return email.trim().toLowerCase();
        }
        return id + "+" + login + "@users.noreply.github.com";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
