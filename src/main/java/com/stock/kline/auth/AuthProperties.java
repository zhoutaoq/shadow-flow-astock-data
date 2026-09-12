package com.stock.kline.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 账号体系相关参数（{@code app.*}）。
 *
 * <p>超管密码走环境变量而不是写在 yml 里 —— 免得把口令提交进仓库：
 * <pre>
 *   java -jar app.jar --ADMIN_PASSWORD=你的口令
 *   # 或 ADMIN_PASSWORD=xxx java -jar app.jar
 * </pre>
 * 不设就是默认值 {@code 1}，只建议本地开发这么用。
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AuthProperties {

    /** 超管账号（用户名固定，初始密码取环境变量） */
    private Admin admin = new Admin();
    /** 登录态相关参数 */
    private Auth auth = new Auth();

    public Admin getAdmin() {
        return admin;
    }

    public void setAdmin(Admin admin) {
        this.admin = admin;
    }

    public Auth getAuth() {
        return auth;
    }

    public void setAuth(Auth auth) {
        this.auth = auth;
    }

    /** 超管账号 */
    public static class Admin {
        /** 超管用户名，固定 admin */
        private String username = "admin";
        /**
         * 超管初始密码。只在「admin 账号还不存在」时用于初始化；
         * 已经存在则保留现状，否则页面改过的密码一重启就被覆盖回去。
         */
        private String password = "1";

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /** 登录与注册 */
    public static class Auth {
        /** 登录态有效时长（小时）；到期后要重新登录 */
        private int sessionHours = 12;
        /** 是否开放注册；false 时只有超管能建账号 */
        private boolean registrationEnabled = true;

        public int getSessionHours() {
            return sessionHours;
        }

        public void setSessionHours(int sessionHours) {
            this.sessionHours = sessionHours;
        }

        public boolean isRegistrationEnabled() {
            return registrationEnabled;
        }

        public void setRegistrationEnabled(boolean registrationEnabled) {
            this.registrationEnabled = registrationEnabled;
        }
    }
}
