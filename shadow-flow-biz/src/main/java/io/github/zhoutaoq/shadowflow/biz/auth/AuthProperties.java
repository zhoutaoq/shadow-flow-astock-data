package io.github.zhoutaoq.shadowflow.biz.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 账号体系相关参数（{@code app.*}）。
 *
 * <p><b>超管口令不写在代码里，也不写在 application.yml 里</b> ——
 * 真实值统一放 classpath 的 {@code app.env}（src/main/resources 下，
 * 已列入 .gitignore 不入库），由启动类 AppEnv.load() 装载成系统属性，
 * application.yml 里只留 {@code ${ADMIN_PASSWORD:}} 占位符。
 * 本类的默认值因此是空串：本地不带 env 直接跑时，admin 不会用任何默认口令建出来。
 *
 * <p>仓库里的模板见同目录的 {@code app.env.example}。
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
         *
         * <p>值来自环境变量 {@code ADMIN_PASSWORD}（application.yml 里配的占位符），
         * 真实值在 classpath 的 {@code app.env}；这里不设明文默认值。
         */
        private String password = "";

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
