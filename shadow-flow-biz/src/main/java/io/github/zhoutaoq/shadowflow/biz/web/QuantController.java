package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantDaily;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantIntradayPoint;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantService;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantStrategy;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantTrade;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量化模拟炒股（需求 v2.0）。
 *
 * <p>接口面与「我的账户」保持同一套习惯：列表 → 单本账 → 持仓 / 流水 / 收益。
 * 所有接口按登录用户隔离，量化策略与手工账户是两套数据，互不影响。
 *
 * <p><b>编辑与生效</b>：{@code PUT} 里的名称、备注、启停立即生效；
 * {@code codes} 与 {@code params} 只写待生效区，次日 0 点由
 * {@code QuantTask} 合并 —— 响应里 {@code pending=true} 表示还有未生效的变更。
 */
@RestController
@RequestMapping("/api/quant")
public class QuantController {

    private final QuantService service;

    public QuantController(QuantService service) {
        this.service = service;
    }

    /* ==================== 策略 ==================== */

    /** 策略列表：每本账一行，带总资产、当日 / 累计收益、持仓数 */
    @GetMapping("/strategies")
    public ApiResponse<List<Map<String, Object>>> list() {
        return ApiResponse.ok(service.list(requireUserId()));
    }

    /**
     * 新建策略：初始资金默认 10 万，范围 1 万 ~ 1000 万。
     *
     * <p>{@code positions} 是初始持仓，形如
     * <code>[{"code":"600584","name":"长电科技","quantity":200,"costPrice":50,"buyDate":"2026-11-04"}]</code>。
     * 这些仓位的成本从初始资金里扣，剩下的才是可用金额；{@code buyDate} 决定当天能不能卖（T+1）。
     */
    @SuppressWarnings("unchecked")
    @PostMapping("/strategies")
    public ApiResponse<QuantStrategy> create(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ApiResponse.fail("策略内容为空");
        }
        List<Map<String, Object>> positions = body.get("positions") instanceof List
                ? (List<Map<String, Object>>) body.get("positions") : null;
        QuantStrategy s = service.create(requireUserId(),
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                decimal(body.get("initCapital")),
                body.get("remark") == null ? null : String.valueOf(body.get("remark")),
                positions);
        return ApiResponse.ok(s, "策略「" + s.getName() + "」已创建");
    }

    /**
     * 编辑策略。
     *
     * <p>{@code codes} / {@code params} 是「次日生效」的，响应消息会明确说出来，
     * 免得用户以为保存完就按新参数在跑。
     */
    @SuppressWarnings("unchecked")
    @PutMapping("/strategies/{id}")
    public ApiResponse<QuantStrategy> update(@PathVariable long id,
                                             @RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return ApiResponse.fail("策略内容为空");
        }
        Map<String, Object> params = body.get("params") instanceof Map
                ? (Map<String, Object>) body.get("params") : null;
        boolean enabled = body.get("enabled") == null || Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        QuantStrategy s = service.update(requireUserId(), id,
                body.get("name") == null ? null : String.valueOf(body.get("name")),
                body.get("remark") == null ? null : String.valueOf(body.get("remark")),
                enabled,
                body.get("codes") == null ? null : String.valueOf(body.get("codes")),
                params,
                decimal(body.get("initCapital")));
        boolean pending = s.getPendingJson() != null && !s.getPendingJson().trim().isEmpty();
        return ApiResponse.ok(s, pending ? "已保存，标的与参数将在次日 0 点生效" : "已保存");
    }

    @DeleteMapping("/strategies/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        service.delete(requireUserId(), id);
        return ApiResponse.ok(null, "策略已删除");
    }

    /* ==================== 持仓与流水 ==================== */

    @GetMapping("/strategies/{id}/positions")
    public ApiResponse<List<Map<String, Object>>> positions(@PathVariable long id) {
        return ApiResponse.ok(service.positions(requireUserId(), id));
    }

    @GetMapping("/strategies/{id}/trades")
    public ApiResponse<List<QuantTrade>> trades(@PathVariable long id,
                                                @RequestParam(required = false) Integer limit) {
        return ApiResponse.ok(service.trades(requireUserId(), id, limit == null ? 200 : limit));
    }

    /* ==================== 收益数据 ==================== */

    /** 每日收益（曲线用） */
    @GetMapping("/strategies/{id}/daily")
    public ApiResponse<List<QuantDaily>> daily(@PathVariable long id,
                                               @RequestParam(required = false) String from,
                                               @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.dailies(requireUserId(), id, from, to));
    }

    /** 每月收益（按月聚合，不单独落盘） */
    @GetMapping("/strategies/{id}/monthly")
    public ApiResponse<List<Map<String, Object>>> monthly(@PathVariable long id,
                                                          @RequestParam(required = false) String from,
                                                          @RequestParam(required = false) String to) {
        return ApiResponse.ok(service.monthly(requireUserId(), id, from, to));
    }

    /** 日内分时（当日资产曲线 + 那一刻的持仓明细） */
    @GetMapping("/strategies/{id}/intraday")
    public ApiResponse<List<QuantIntradayPoint>> intraday(@PathVariable long id,
                                                          @RequestParam(required = false) String date) {
        return ApiResponse.ok(service.intraday(requireUserId(), id, date));
    }

    /* ==================== 超管 ==================== */

    /** 需求第 8 条：超管手动触发初始化，立即刷新标的池与 T+1 可用数量 */
    @PostMapping("/admin/init")
    public ApiResponse<Map<String, Object>> init() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以手动初始化");
        }
        Map<String, Object> out = new LinkedHashMap<>(service.initNow());
        return ApiResponse.ok(out, "初始化完成");
    }

    /* ==================== 工具 ==================== */

    /**
     * 量化策略只对超管开放。
     *
     * <p>菜单隐藏只是展示层，直接敲 URL 照样能进来 —— 所以每个接口都在这里卡一道，
     * 普通用户连列表都拉不到。
     */
    private static long requireUserId() {
        long userId = AuthContext.currentUserId();
        if (userId <= 0) {
            throw new IllegalArgumentException("请先登录");
        }
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("量化策略仅对超管开放");
        }
        return userId;
    }

    private static BigDecimal decimal(Object v) {
        if (v == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
