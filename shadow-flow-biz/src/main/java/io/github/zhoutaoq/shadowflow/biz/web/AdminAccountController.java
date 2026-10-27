package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.account.AccountService;
import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.auth.model.User;
import io.github.zhoutaoq.shadowflow.biz.auth.model.UserView;
import io.github.zhoutaoq.shadowflow.biz.auth.repo.UserRepository;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.service.AdminAccountPreviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 账户管理（v1.7.9）：超管按用户查看其账户，只读。
 *
 * <p>与「代理登录」的区别：代理登录是<b>换一份登录态</b>，看到的是对方视角下的整站，
 * 而且只能一本本地翻；这里不换身份、不跳页，直接把对方的账户以卡片摆出来，
 * 用的还是 {@code AccountService} 里同一套口径（现价、当日盈亏、总盈亏）。
 *
 * <p>两个「不」：
 * <ul>
 *   <li>不建默认账户 —— 走 {@code overviewReadOnly}，对方没有账户就返回空；</li>
 *   <li>不回落 —— 账户 id 不属于该用户时直接拒绝，不悄悄换成对方的第一本账。</li>
 * </ul>
 * 也不提供任何写账户的接口：超管看别人的账只做核对，改还是得让本人来。
 *
 * <p>v1.8.0 加「默认预览用户」：配好之后页面平铺这些人的所有账户一起看
 * （{@code /board}），配置本身也是系统级一行，读写都在这里。
 */
@RestController
@RequestMapping("/api/admin/accounts")
public class AdminAccountController {

    private final AccountService accounts;
    private final UserRepository users;
    private final AdminAccountPreviewService preview;

    public AdminAccountController(AccountService accounts, UserRepository users,
                                  AdminAccountPreviewService preview) {
        this.accounts = accounts;
        this.users = users;
        this.preview = preview;
    }

    /** 某个用户的账户列表：每本账一张卡片 + 全部合计（对应「我的账户」一级） */
    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview(@RequestParam("userId") long userId) {
        requireAdmin();
        User target = requireUser(userId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", UserView.of(target));
        out.put("overview", accounts.overviewReadOnly(userId));
        return ApiResponse.ok(out);
    }

    /** 某个用户某本账的明细：账户统计 + 持仓 + 操作记录（对应「我的账户」二级，只读） */
    @GetMapping("/detail")
    public ApiResponse<Map<String, Object>> detail(@RequestParam("userId") long userId,
                                                   @RequestParam("accountId") long accountId) {
        requireAdmin();
        User target = requireUser(userId);
        if (accounts.findAccount(userId, accountId) == null) {
            return ApiResponse.fail("账户不存在，或已不属于该用户");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", UserView.of(target));
        out.put("summary", accounts.summary(userId, accountId));
        out.put("records", accounts.listRecords(userId, accountId));
        return ApiResponse.ok(out);
    }

    /* ==================== 默认预览用户（v1.8.0） ==================== */

    /** 当前的默认预览用户（没配过就是空列表，页面按单个用户查看） */
    @GetMapping("/preview")
    public ApiResponse<Map<String, Object>> getPreview() {
        requireAdmin();
        return ApiResponse.ok(previewPayload());
    }

    /**
     * 保存默认预览用户：整份覆盖，传空数组即清空。
     *
     * <p>保存后页面下次进入（或点「重新载入」）就平铺这些人名下所有账户。
     */
    @PutMapping("/preview")
    public ApiResponse<Map<String, Object>> savePreview(@RequestBody(required = false) Map<String, Object> body) {
        requireAdmin();
        List<Long> ids = new ArrayList<>();
        Object raw = body == null ? null : body.get("userIds");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (o instanceof Number) {
                    ids.add(((Number) o).longValue());
                } else if (o != null) {
                    try {
                        ids.add(Long.parseLong(String.valueOf(o).trim()));
                    } catch (NumberFormatException ignored) {
                        // 单个非法值忽略，别让整次保存失败
                    }
                }
            }
        }
        preview.savePreview(ids);
        return ApiResponse.ok(previewPayload(), ids.isEmpty() ? "已清空默认预览用户" : "默认预览用户已保存");
    }

    /** 平铺视图：默认预览用户 × 他们名下所有账户，一次取回 */
    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        requireAdmin();
        return ApiResponse.ok(preview.board());
    }

    private Map<String, Object> previewPayload() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userIds", preview.previewUserIds());
        out.put("users", preview.previewUsers());
        return out;
    }

    private static void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以查看其它用户的账户");
        }
    }

    private User requireUser(long userId) {
        User u = users.findById(userId);
        if (u == null) {
            throw new IllegalArgumentException("用户不存在");
        }
        return u;
    }
}
