package io.github.zhoutaoq.shadowflow.biz.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhoutaoq.shadowflow.biz.auth.model.GitHubProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * GitHub OAuth 授权码流程（协议层）：发起授权、换 token、拉用户资料。
 *
 * <p>三个环节：
 * <ol>
 *   <li>{@link #buildAuthorizeUrl} 拼 GitHub 授权页地址，顺手把一次性 state 记进内存
 *       （防 CSRF：回调必须带着同一个 state 回来，且只能用一次）；</li>
 *   <li>{@link #exchangeCode} 用 code 换 access token（这一步在服务端完成，
 *       client_secret 不经过浏览器）；</li>
 *   <li>{@link #fetchProfile} 拉 {@code /user} 与 {@code /user/emails}，
 *       挑「已验证主邮箱」组装 {@link GitHubProfile}。</li>
 * </ol>
 *
 * <p>state 只放内存（与 {@link SessionService} 同一取舍）：进程重启则旧授权链路作废，
 * 用户重新点一次登录即可，不值得为它引一张表。state 五分钟内有效，用一次就删。
 */
@Service
public class GitHubOAuthService {

    private static final Logger log = LoggerFactory.getLogger(GitHubOAuthService.class);

    /** state 有效期（分钟）：正常授权往返远用不了这么久，够容忍用户在 GitHub 页上犹豫一会儿 */
    private static final long STATE_TTL_MILLIS = 5 * 60 * 1000L;

    private final GitHubOAuthProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 一次性 state → 生成时刻 + 登录完成后要回的站内路径 */
    private final ConcurrentMap<String, StateEntry> states = new ConcurrentHashMap<>();

    public GitHubOAuthService(GitHubOAuthProperties props) {
        this.props = props;
    }

    public boolean isConfigured() {
        return props.isConfigured();
    }

    /**
     * 拼 GitHub 授权页地址，并登记一次性 state。
     *
     * @param redirectUri 本服务的回调地址（必须与 GitHub 后台登记的逐字符一致）
     * @param returnTo    登录完成后要回的站内路径（只收 {@code /} 开头的相对路径，防开放重定向）；
     *                    空 / 不合法时回首页
     */
    public String buildAuthorizeUrl(String redirectUri, String returnTo) {
        String state = UUID.randomUUID().toString().replace("-", "");
        sweep();
        states.put(state, new StateEntry(System.currentTimeMillis(), safeReturnTo(returnTo)));
        StringBuilder sb = new StringBuilder(props.getWebBaseUrl()).append("/login/oauth/authorize");
        sb.append("?client_id=").append(enc(props.getClientId()));
        sb.append("&redirect_uri=").append(enc(redirectUri));
        sb.append("&scope=").append(enc(props.getScope()));
        sb.append("&state=").append(enc(state));
        return sb.toString();
    }

    /**
     * 校验并消费 state（一次有效）。
     *
     * @return state 里记的 returnTo（已做过滤，不会是外站地址）
     * @throws IllegalArgumentException state 缺失 / 不存在 / 已用过 / 已过期
     */
    public String consumeState(String state) {
        if (!StringUtils.hasText(state)) {
            throw new IllegalArgumentException("缺少登录校验码（state），请从登录页重新发起");
        }
        StateEntry entry = states.remove(state.trim());
        if (entry == null) {
            throw new IllegalArgumentException("登录校验码已失效，请重新发起登录");
        }
        if (System.currentTimeMillis() - entry.born > STATE_TTL_MILLIS) {
            throw new IllegalArgumentException("登录已超时，请重新发起");
        }
        return entry.returnTo;
    }

    /** returnTo 只认站内相对路径：防开放重定向（//evil.com 也要挡） */
    private static String safeReturnTo(String returnTo) {
        if (returnTo == null) {
            return "/";
        }
        String t = returnTo.trim();
        if (t.isEmpty() || !t.startsWith("/") || t.startsWith("//") || t.contains("\\")) {
            return "/";
        }
        return t;
    }

    /**
     * 用授权码换 access token，再拉用户资料。
     *
     * @return GitHub 侧用户资料（email 为已验证主邮箱，可能为空）
     * @throws IOException          网络不通 / GitHub 报错
     * @throws IllegalArgumentException GitHub 返回业务错误（如 code 过期）
     */
    public GitHubProfile fetchProfile(String code, String redirectUri) throws IOException {
        String token = exchangeCode(code, redirectUri);
        JsonNode user = getJson(props.getApiBaseUrl() + "/user", token);
        GitHubProfile p = new GitHubProfile();
        p.setId(text(user, "id"));
        p.setLogin(text(user, "login"));
        p.setName(text(user, "name"));
        p.setEmail(primaryVerifiedEmail(token, text(user, "email")));
        return p;
    }

    /**
     * 换 token：失败重试，最多 3 次。
     *
     * <p>这台服务器到 github.com 的链路是「成块抖动」：几分钟内连不上，
     * 过一会儿又全通（见 2026-10-21 排查记录）。单次失败不代表授权码无效，
     * 也不能只试一次就判死 —— 换 token 失败时 GitHub 侧不会消费掉 code，
     * 重试是安全的。单次超时压到 6 秒，同样 20 秒的总时长里能多试一轮。
     */
    private String exchangeCode(String code, String redirectUri) throws IOException {
        final int maxAttempts = 3;
        final long[] backoffMs = {500L, 1500L};
        IOException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return exchangeOnce(code, redirectUri);
            } catch (IOException e) {
                last = e;
                log.warn("[auth] GitHub 换 token 失败（第 {}/{} 次）：{}", attempt, maxAttempts, e.toString());
                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(backoffMs[Math.min(attempt - 1, backoffMs.length - 1)]);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    /** POST 换 token；GitHub 用 JSON 回 {access_token} 或 {error, error_description} */
    private String exchangeOnce(String code, String redirectUri) throws IOException {
        String body = "client_id=" + enc(props.getClientId())
                + "&client_secret=" + enc(props.getClientSecret())
                + "&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri);
        HttpURLConnection conn = open(props.exchangeBaseUrl() + "/login/oauth/access_token");
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("Accept", "application/json");
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        JsonNode node = readJson(conn);
        String token = text(node, "access_token");
        if (!StringUtils.hasText(token)) {
            String err = text(node, "error_description");
            if (!StringUtils.hasText(err)) {
                err = text(node, "error");
            }
            throw new IllegalArgumentException(StringUtils.hasText(err)
                    ? "GitHub 授权失败：" + err : "GitHub 授权失败，未返回访问令牌");
        }
        return token;
    }

    /**
     * 挑「已验证的主邮箱」。
     *
     * <p>{@code /user} 里的 email 对很多人是 null（资料里没公开邮箱），所以要再查
     * {@code /user/emails}；一个都挑不到就留空，由 {@link GitHubProfile#resolveUsername()}
     * 用 noreply 地址兜底 —— 用户名必须是邮箱，不能让「没公开邮箱的人」登录不了。
     */
    private String primaryVerifiedEmail(String token, String fallback) throws IOException {
        try {
            JsonNode arr = getJson(props.getApiBaseUrl() + "/user/emails", token);
            String firstVerified = null;
            if (arr != null && arr.isArray()) {
                for (JsonNode e : arr) {
                    boolean verified = e.path("verified").asBoolean(false);
                    if (!verified) {
                        continue;
                    }
                    String email = text(e, "email");
                    if (e.path("primary").asBoolean(false) && StringUtils.hasText(email)) {
                        return email;
                    }
                    if (firstVerified == null && StringUtils.hasText(email)) {
                        firstVerified = email;
                    }
                }
            }
            return firstVerified != null ? firstVerified : fallback;
        } catch (IOException e) {
            // 邮箱接口拿不到不该拦住登录：/user 自带回显邮箱时照样能用
            log.warn("[auth] GitHub 邮箱接口调用失败，回落 /user 的 email：{}", e.getMessage());
            return fallback;
        }
    }

    private JsonNode getJson(String urlStr, String token) throws IOException {
        HttpURLConnection conn = open(urlStr);
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        return readJson(conn);
    }

    private HttpURLConnection open(String urlStr) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(props.getConnectTimeoutMs());
        conn.setReadTimeout(props.getReadTimeoutMs());
        // GitHub API 要求带 User-Agent，缺了直接 403
        conn.setRequestProperty("User-Agent", "shadow-flow-biz");
        return conn;
    }

    private JsonNode readJson(HttpURLConnection conn) throws IOException {
        int status = conn.getResponseCode();
        InputStream stream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            throw new IOException("HTTP " + status + " 无响应体");
        }
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            String body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            if (status >= 400) {
                throw new IOException("HTTP " + status + ", body=" + body);
            }
            return mapper.readTree(body);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.path(field).isMissingNode() || node.path(field).isNull()) {
            return null;
        }
        String v = node.path(field).asText(null);
        return v == null || v.trim().isEmpty() ? null : v.trim();
    }

    private void sweep() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, StateEntry> e : states.entrySet()) {
            if (now - e.getValue().born > STATE_TTL_MILLIS) {
                states.remove(e.getKey());
            }
        }
    }

    /** 一个在途授权：记生成时刻（过期判定）与登录完成后的回跳路径 */
    private static final class StateEntry {
        final long born;
        final String returnTo;

        StateEntry(long born, String returnTo) {
            this.born = born;
            this.returnTo = returnTo;
        }
    }

    private static String enc(String v) {
        if (v == null) {
            return "";
        }
        try {
            return URLEncoder.encode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return v;
        }
    }
}
