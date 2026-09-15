package com.stock.kline.web;

import com.stock.kline.account.Account;
import com.stock.kline.account.AccountService;
import com.stock.kline.account.AccountSummary;
import com.stock.kline.account.Position;
import com.stock.kline.account.TradeRecord;
import com.stock.kline.auth.AuthContext;
import com.stock.kline.model.ApiResponse;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 我的账户（需求 v1.5.1 第 ③ 条）：登录后读写自己那份账户与持仓。
 *
 * <p>接口面：
 * <ul>
 *   <li>{@code GET  /api/account/summary}    账户 + 持仓 + 收益的合成视图（首页与账户页共用）；</li>
 *   <li>{@code GET  /api/account}            账户设置（期初市值 / 可用金额）；</li>
 *   <li>{@code PUT  /api/account}            保存账户设置；</li>
 *   <li>{@code GET  /api/account/positions}  持仓列表；</li>
 *   <li>{@code POST /api/account/positions}  新增持仓；</li>
 *   <li>{@code PUT  /api/account/positions/{id}} 修改持仓；</li>
 *   <li>{@code DELETE /api/account/positions/{id}} 删除持仓；</li>
 *   <li>{@code DELETE /api/account/positions} 清空持仓；</li>
 *   <li>{@code GET  /api/account/records}    操作记录（内存、不落盘）；</li>
 *   <li>{@code POST /api/account/records}    记一笔买 / 卖，实时生效；</li>
 *   <li>{@code DELETE /api/account/records}  清空操作记录。</li>
 * </ul>
 *
 * <p>所有接口都取当前登录用户，账户数据严格按用户隔离。
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountService service;

    public AccountController(AccountService service) {
        this.service = service;
    }

    /** 账户汇总：首页「我的账户」区块与账户页都用它 */
    @GetMapping("/summary")
    public ApiResponse<AccountSummary> summary() {
        return ApiResponse.ok(service.summary(requireUserId()));
    }

    @GetMapping
    public ApiResponse<Account> get() {
        return ApiResponse.ok(service.getAccount(requireUserId()));
    }

    @PutMapping
    public ApiResponse<Account> save(@RequestBody(required = false) Account body) {
        if (body == null) {
            return ApiResponse.fail("账户内容为空");
        }
        return ApiResponse.ok(service.saveAccount(requireUserId(), body), "账户设置已保存");
    }

    @GetMapping("/positions")
    public ApiResponse<List<Position>> positions() {
        return ApiResponse.ok(service.listPositions(requireUserId()));
    }

    @PostMapping("/positions")
    public ApiResponse<Position> addPosition(@RequestBody(required = false) Position body) {
        if (body == null) {
            return ApiResponse.fail("持仓内容为空");
        }
        body.setId(null);
        return ApiResponse.ok(service.savePosition(requireUserId(), body), "持仓已添加");
    }

    @PutMapping("/positions/{id}")
    public ApiResponse<Position> updatePosition(@PathVariable long id,
                                                @RequestBody(required = false) Position body) {
        if (body == null) {
            return ApiResponse.fail("持仓内容为空");
        }
        body.setId(id);
        return ApiResponse.ok(service.savePosition(requireUserId(), body), "持仓已更新");
    }

    @DeleteMapping("/positions/{id}")
    public ApiResponse<Void> deletePosition(@PathVariable long id) {
        service.deletePosition(requireUserId(), id);
        return ApiResponse.ok(null, "持仓已删除");
    }

    @DeleteMapping("/positions")
    public ApiResponse<Void> clearPositions() {
        service.clearPositions(requireUserId());
        return ApiResponse.ok(null, "持仓已清空");
    }

    @GetMapping("/records")
    public ApiResponse<List<TradeRecord>> records() {
        return ApiResponse.ok(service.listRecords(requireUserId()));
    }

    /**
     * 记一笔买 / 卖：实时生效（改持仓 + 改可用金额 + 买入选/卖出移盯盘），不落盘。
     *
     * <p>请求体形如 <code>{"action":"buy","code":"600000","quantity":100,"price":10.5}</code>。
     * 已有持仓时 {@code price} 会被忽略（一律按持仓成本价记账）；买入一只尚无持仓的标的时
     * {@code price} 是这次建仓的成本价（页面预填实时行情价，可手改）。
     */
    @PostMapping("/records")
    public ApiResponse<TradeRecord> addRecord(@RequestBody(required = false) Map<String, Object> body) {
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
        TradeRecord r = service.addRecord(requireUserId(),
                act == null ? null : String.valueOf(act),
                code == null ? null : String.valueOf(code),
                name == null ? null : String.valueOf(name),
                quantity,
                price);
        return ApiResponse.ok(r, (TradeRecord.ACTION_BUY.equals(r.getAction()) ? "买入" : "卖出")
                + "已生效：可用金额 " + r.getCashAfter());
    }

    /**
     * 「记一笔操作」表单的价格预填：已有持仓返回成本价，否则取该标的实时行情价；
     * 都拿不到时 {@code price} 为 null，页面留空由用户填实际成交价。
     */
    @GetMapping("/price")
    public ApiResponse<Map<String, Object>> price(@RequestParam("code") String code) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("price", service.suggestPrice(requireUserId(), code));
        return ApiResponse.ok(out);
    }

    @DeleteMapping("/records")
    public ApiResponse<Void> clearRecords() {
        service.clearRecords(requireUserId());
        return ApiResponse.ok(null, "操作记录已清空");
    }

    /** 页面要展示的输入范围，与 AccountService 的校验保持一致 */
    @GetMapping("/limits")
    public ApiResponse<Map<String, Object>> limits() {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("maxPositions", 200);
        limits.put("maxRecords", 200);
        List<String> actions = new ArrayList<>();
        actions.add(TradeRecord.ACTION_BUY);
        actions.add(TradeRecord.ACTION_SELL);
        limits.put("actions", actions);
        return ApiResponse.ok(limits);
    }

    /** 当前登录用户：拦截器已保证登录，这里再判一次防止路径被摘出拦截器后拿到 0 号用户的数据 */
    private static long requireUserId() {
        long userId = AuthContext.currentUserId();
        if (userId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        return userId;
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
}
