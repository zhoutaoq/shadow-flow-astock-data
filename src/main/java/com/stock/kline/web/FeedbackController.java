package com.stock.kline.web;

import com.stock.kline.feedback.FeedbackService;
import com.stock.kline.model.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/**
 * 用户使用反馈：页面提交 → 推一条钉钉（含 IP / 时间 / 内容）。
 *
 * <p>接口对所有人开放（反馈的人不该先过密码），所以除了内容长度与频率，
 * 还要靠 {@link FeedbackService} 里的同 IP 冷却挡住误连点和刷屏。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService service;

    public FeedbackController(FeedbackService service) {
        this.service = service;
    }

    /**
     * 反馈入口的当前限制（是否开放、内容上限、提交冷却秒数）。
     *
     * <p>页面用它设置 textarea 的 maxlength 与提示文案；取不到就按页面内置默认值走，
     * 不影响提交（提交时的校验始终以后端为准）。
     */
    @GetMapping("/limits")
    public ApiResponse<Map<String, Object>> limits() {
        return ApiResponse.ok(service.limits());
    }

    /**
     * 提交反馈。
     *
     * <p>校验失败 / 推送失败都由 service 抛 {@link IllegalArgumentException}，
     * 走 {@code GlobalExceptionHandler} 统一转成 {@code {success:false,message}}。
     *
     * @param body 请求体，只读一个 <code>content</code> 字段
     */
    @PostMapping
    public ApiResponse<Void> submit(@RequestBody(required = false) Map<String, Object> body,
                                    HttpServletRequest request) {
        Object raw = body == null ? null : body.get("content");
        String content = raw == null ? null : String.valueOf(raw);
        service.submit(content, clientIp(request));
        return ApiResponse.ok(null, "感谢反馈，已收到");
    }

    /**
     * 取客户端 IP。
     *
     * <p>生产上 java 直接监听 80、没过 nginx，{@code getRemoteAddr()} 就是真实来源；
     * 这里仍按「X-Forwarded-For → X-Real-IP → Proxy-Client-IP → remoteAddr」的顺序兜底，
     * 万一以后前面挂了反向代理也不会把每个人的 IP 都记成代理的地址。
     */
    private static String clientIp(HttpServletRequest request) {
        String ip = first(request.getHeader("X-Forwarded-For"));
        if (ip == null) {
            ip = first(request.getHeader("X-Real-IP"));
        }
        if (ip == null) {
            ip = first(request.getHeader("Proxy-Client-IP"));
        }
        if (ip == null) {
            ip = first(request.getRemoteAddr());
        }
        return normalize(ip);
    }

    /** X-Forwarded-For 可能是「真实客户端, 代理1, 代理2」，只取第一段 */
    private static String first(String header) {
        if (header == null) {
            return null;
        }
        for (String part : header.split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && !"unknown".equalsIgnoreCase(s)) {
                return s;
            }
        }
        return null;
    }

    /** 归一化：本机回环统一成 127.0.0.1，去掉 IPv6 映射前缀与可能带的端口 */
    private static String normalize(String ip) {
        if (ip == null || ip.isEmpty()) {
            return "未知";
        }
        String s = ip.trim();
        if ("::1".equals(s) || "0:0:0:0:0:0:0:1".equals(s)) {
            return "127.0.0.1";
        }
        if (s.startsWith("::ffff:")) {
            s = s.substring("::ffff:".length());
        }
        if (s.startsWith("[") && s.endsWith("]")) {
            s = s.substring(1, s.length() - 1);
        }
        int colon = s.lastIndexOf(':');
        if (colon > 0 && s.indexOf(':') == colon && s.indexOf('.') > 0) {
            // 形如 1.2.3.4:5678 的 IPv4:端口，端口去掉
            s = s.substring(0, colon);
        }
        return s.isEmpty() ? "未知" : s;
    }
}
