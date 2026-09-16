package com.stock.kline.web;

import com.stock.kline.account.Account;
import com.stock.kline.account.AccountOverview;
import com.stock.kline.account.AccountService;
import com.stock.kline.account.AccountSummary;
import com.stock.kline.account.AccountTransferService;
import com.stock.kline.account.Position;
import com.stock.kline.account.TradeRecord;
import com.stock.kline.auth.AuthContext;
import com.stock.kline.model.ApiResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的账户（需求 v1.5.1 第 ③ 条、v1.5.2 第 ④ 条）：登录后读写自己那份账户。
 *
 * <p>两级模型（参考同花顺）：
 * <ul>
 *   <li><b>一级 —— 账户</b>：
 *       {@code GET/POST /api/account/accounts} 列表与新建、
 *       {@code PUT/DELETE /api/account/accounts/{id}} 改名与删除；</li>
 *   <li><b>二级 —— 账户下的持仓与记录</b>：下面所有接口用 {@code accountId} 定位，
 *       不带（或传 0）时回落到第一本账，老前端调用不需要改。</li>
 * </ul>
 *
 * <p>接口面：
 * <ul>
 *   <li>{@code GET  /api/account/summary}    汇总：带 accountId = 该本账，不带 = 全部账户合计（首页用）；</li>
 *   <li>{@code GET  /api/account}            账户设置（名称 / 期初市值 / 可用金额）；</li>
 *   <li>{@code PUT  /api/account}            保存账户设置；</li>
 *   <li>{@code GET  /api/account/positions}  持仓列表；</li>
 *   <li>{@code POST /api/account/positions}  新增持仓；</li>
 *   <li>{@code PUT  /api/account/positions/{id}} 修改持仓；</li>
 *   <li>{@code DELETE /api/account/positions/{id}} 删除持仓；</li>
 *   <li>{@code DELETE /api/account/positions} 清空持仓；</li>
 *   <li>{@code GET  /api/account/records}    操作记录（v1.5.6 起落盘，重启不丢）；</li>
 *   <li>{@code POST /api/account/records}    记一笔买 / 卖，实时生效；</li>
 *   <li>{@code DELETE /api/account/records}  清空操作记录；</li>
 *   <li>{@code GET  /api/account/export}     导出单个账户（zip：账户 + 持仓 + 操作记录）；</li>
 *   <li>{@code POST /api/account/import}     导入账户导出文件（新建一本账）。</li>
 * </ul>
 *
 * <p>所有接口都取当前登录用户，账户数据严格按用户隔离。
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService service;
    private final AccountTransferService transfer;

    public AccountController(AccountService service, AccountTransferService transfer) {
        this.service = service;
        this.transfer = transfer;
    }

    /* ==================== 一级：账户 ==================== */

    /** 账户列表：每本账一行（总市值 / 当日收益 / 总收益 / 持仓数）+ 全部合计 */
    @GetMapping("/accounts")
    public ApiResponse<AccountOverview> accounts() {
        return ApiResponse.ok(service.overview(requireUserId()));
    }

    /** 新建一本账 */
    @PostMapping("/accounts")
    public ApiResponse<Account> createAccount(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ApiResponse.fail("账户内容为空");
        }
        Account a = service.createAccount(requireUserId(),
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                decimal(body.get("initValue")),
                decimal(body.get("cash")));
        return ApiResponse.ok(a, "账户「" + a.getName() + "」已创建");
    }

    /** 改名 / 改期初市值 / 改可用金额 */
    @PutMapping("/accounts/{id}")
    public ApiResponse<Account> saveAccountById(@PathVariable long id,
                                                @RequestBody(required = false) Account body) {
        if (body == null) {
            return ApiResponse.fail("账户内容为空");
        }
        return ApiResponse.ok(service.saveAccount(requireUserId(), id, body), "账户设置已保存");
    }

    /** 删除一本账（连同它的持仓与操作记录） */
    @DeleteMapping("/accounts/{id}")
    public ApiResponse<Void> deleteAccount(@PathVariable long id) {
        service.deleteAccount(requireUserId(), id);
        return ApiResponse.ok(null, "账户已删除");
    }

    /* ==================== 二级：账户下的持仓与收益 ==================== */

    /** 汇总：带 accountId = 该本账；不带 = 全部账户合计（首页「我的账户」区块） */
    @GetMapping("/summary")
    public ApiResponse<AccountSummary> summary(@RequestParam(value = "accountId", required = false) Long accountId) {
        long userId = requireUserId();
        if (accountId == null || accountId <= 0) {
            return ApiResponse.ok(service.summary(userId));
        }
        return ApiResponse.ok(service.summary(userId, accountId));
    }

    @GetMapping
    public ApiResponse<Account> get(@RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResponse.ok(service.getAccount(requireUserId(), accountId));
    }

    @PutMapping
    public ApiResponse<Account> save(@RequestParam(value = "accountId", required = false) Long accountId,
                                     @RequestBody(required = false) Account body) {
        if (body == null) {
            return ApiResponse.fail("账户内容为空");
        }
        long userId = requireUserId();
        return ApiResponse.ok(service.saveAccount(userId, service.resolveAccountId(userId, accountId), body),
                "账户设置已保存");
    }

    @GetMapping("/positions")
    public ApiResponse<List<Position>> positions(@RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResponse.ok(service.listPositions(requireUserId(), requireAccountId(accountId)));
    }

    @PostMapping("/positions")
    public ApiResponse<Position> addPosition(@RequestParam(value = "accountId", required = false) Long accountId,
                                             @RequestBody(required = false) Position body) {
        if (body == null) {
            return ApiResponse.fail("持仓内容为空");
        }
        body.setId(null);
        return ApiResponse.ok(service.savePosition(requireUserId(), requireAccountId(accountId), body), "持仓已添加");
    }

    @PutMapping("/positions/{id}")
    public ApiResponse<Position> updatePosition(@PathVariable long id,
                                                @RequestParam(value = "accountId", required = false) Long accountId,
                                                @RequestBody(required = false) Position body) {
        if (body == null) {
            return ApiResponse.fail("持仓内容为空");
        }
        body.setId(id);
        return ApiResponse.ok(service.savePosition(requireUserId(), requireAccountId(accountId), body), "持仓已更新");
    }

    @DeleteMapping("/positions/{id}")
    public ApiResponse<Void> deletePosition(@PathVariable long id,
                                            @RequestParam(value = "accountId", required = false) Long accountId) {
        service.deletePosition(requireUserId(), requireAccountId(accountId), id);
        return ApiResponse.ok(null, "持仓已删除");
    }

    @DeleteMapping("/positions")
    public ApiResponse<Void> clearPositions(@RequestParam(value = "accountId", required = false) Long accountId) {
        service.clearPositions(requireUserId(), requireAccountId(accountId));
        return ApiResponse.ok(null, "持仓已清空");
    }

    @GetMapping("/records")
    public ApiResponse<List<TradeRecord>> records(@RequestParam(value = "accountId", required = false) Long accountId) {
        return ApiResponse.ok(service.listRecords(requireUserId(), requireAccountId(accountId)));
    }

    /**
     * 记一笔买 / 卖：实时生效（改持仓 + 改可用金额 + 记当日已实现 + 买入选/卖出移盯盘），落盘。
     *
     * <p>请求体形如
     * <code>{"action":"buy","code":"600000","quantity":100,"price":10.5,"today":true,"date":"2026-09-16"}</code>。
     * {@code price} 是实际成交价（页面预填实时行情价，可手改），买卖都按它动可用金额；
     * 缺省时回落到持仓成本价。{@code today} 是「当天买入 / 卖的是当天买入的」标记，
     * 缺省按 {@code true}：一旦勾上，这笔买入的当日盈亏从买入价起算、
     * 这笔卖出按当天买入均价算已实现（详见 {@code AccountService#addRecord}）。
     * {@code date} 是成交日期（v1.5.6），缺省即今天；填成过去的日期按补录处理，
     * 只改持仓与可用金额，不动「当天买入池」与当日已实现。
     */
    @PostMapping("/records")
    public ApiResponse<TradeRecord> addRecord(@RequestParam(value = "accountId", required = false) Long accountId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ApiResponse.fail("操作内容为空");
        }
        Object act = body.get("action");
        Object code = body.get("code");
        Object qty = body.get("quantity");
        Integer quantity = null;
        if (qty instanceof Number) {
            quantity = ((Number) qty).intValue();
        } else if (qty != null) {
            try {
                quantity = new BigDecimal(String.valueOf(qty).trim()).intValue();
            } catch (NumberFormatException e) {
                return ApiResponse.fail("数量必须是数字");
            }
        }
        BigDecimal price = decimal(body.get("price"));
        Object name = body.get("name");
        Object date = body.get("date");
        TradeRecord r = service.addRecord(requireUserId(), requireAccountId(accountId),
                act == null ? null : String.valueOf(act),
                code == null ? null : String.valueOf(code),
                name == null ? null : String.valueOf(name),
                quantity,
                price,
                bool(body.get("today")),
                date == null ? null : String.valueOf(date));
        return ApiResponse.ok(r, (TradeRecord.ACTION_BUY.equals(r.getAction()) ? "买入" : "卖出")
                + "已生效：可用金额 " + r.getCashAfter());
    }

    /**
     * 「记一笔操作」表单的价格预填：已有持仓返回成本价，否则取该标的实时行情价；
     * 都拿不到时 {@code price} 为 null，页面留空由用户填实际成交价。
     */
    @GetMapping("/price")
    public ApiResponse<Map<String, Object>> price(@RequestParam("code") String code,
                                                  @RequestParam(value = "accountId", required = false) Long accountId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("price", service.suggestPrice(requireUserId(), requireAccountId(accountId), code));
        return ApiResponse.ok(out);
    }

    @DeleteMapping("/records")
    public ApiResponse<Void> clearRecords(@RequestParam(value = "accountId", required = false) Long accountId) {
        service.clearRecords(requireUserId(), requireAccountId(accountId));
        return ApiResponse.ok(null, "操作记录已清空");
    }

    /** 页面要展示的输入范围，与 AccountService 的校验保持一致 */
    @GetMapping("/limits")
    public ApiResponse<Map<String, Object>> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxPositions", 200);
        limits.put("maxRecords", 200);
        limits.put("maxAccounts", 10);
        limits.put("maxNameLength", 32);
        List<String> actions = new ArrayList<>();
        actions.add(TradeRecord.ACTION_BUY);
        actions.add(TradeRecord.ACTION_SELL);
        limits.put("actions", actions);
        return ApiResponse.ok(limits);
    }

    /* ==================== 单账户导出 / 导入（v1.5.6 第 ③ 条） ==================== */

    /**
     * 导出某个账户：账户情况 + 持仓情况 + 操作记录，打包成一个 zip 下载。
     *
     * <p>返回的是文件流而不是 {@code ApiResponse}，前端按 blob 接、拼文件名存盘。
     * 文件名走 RFC 5987（账户名是中文，直接塞进 header 会乱码）。
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(value = "accountId", required = false) Long accountId) {
        long userId = requireUserId();
        long aid = requireAccountId(accountId);
        byte[] body = transfer.exportZip(userId, aid);
        ContentDisposition cd = ContentDisposition.attachment()
                .filename(transfer.exportFileName(userId, aid), StandardCharsets.UTF_8)
                .build();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDisposition(cd);
        headers.setContentLength(body.length);
        return new ResponseEntity<>(body, headers, HttpStatus.OK);
    }

    /**
     * 导入一个账户导出文件（zip）：<b>新建一本账</b>，不动已有的任何一本。
     *
     * <p>账户名与现有账户重名时自动加「（导入）」后缀。
     */
    @PostMapping("/import")
    public ApiResponse<Account> importAccount(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("请选择要导入的文件");
        }
        try {
            Account a = transfer.importZip(requireUserId(), file.getBytes());
            return ApiResponse.ok(a, "已导入为账户「" + a.getName() + "」");
        } catch (IOException e) {
            return ApiResponse.fail("文件读取失败：" + e.getMessage());
        }
    }

    /* ==================== 内部 ==================== */

    /** 当前登录用户：拦截器已保证登录，这里再判一次防止路径被摘出拦截器后拿到 0 号用户的数据 */
    private static long requireUserId() {
        long userId = AuthContext.currentUserId();
        if (userId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
    }

    /** 二级接口的账户定位：不带 accountId 时回落到第一本账（老前端调用照旧可用） */
    private long requireAccountId(Long accountId) {
        return service.resolveAccountId(requireUserId(), accountId);
    }

    /** 请求体里的数字宽松解析：null / 空串 / 非法一律给 null，由业务层校验 */
    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Number) {
            return new BigDecimal(String.valueOf(v));
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 请求体里的布尔宽松解析：「当天买入」标记走它。
     *
     * <p>表单/JSON 都可能给 {@code true} / {@code "true"} / {@code 1} / {@code "1"}；
     * 缺省（null）返回 {@code null}，由业务层决定默认值（当天）。
     */
    private static Boolean bool(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) {
            return null;
        }
        if ("true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(s) || "0".equals(s) || "no".equalsIgnoreCase(s)) {
            return Boolean.FALSE;
        }
        return null;
    }
}
