package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantDecisionService;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantHolding;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantMetrics;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantQuote;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantRepository;
import io.github.zhoutaoq.shadowflow.biz.quant.QuantSnapshotService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 验证用：喂一份假行情 + 假指标，把「L1 通过之后」的整条买入链路跑出来。
 *
 * <p><b>默认不注册</b>：只有 {@code shadowflow.quant.mock-enabled=true} 时这个 Bean 才存在，
 * 生产环境不会多出一个能凭空下单的接口。
 *
 * <p>存在的理由：L1 是三道硬门槛（大盘 / 主力 / 位置），真实行情里要正好全部通过才有买点，
 * 买入链路因此长期没被跑通过 —— 没有验证过的下单逻辑比没有更危险。
 */
@RestController
@RequestMapping("/api/quant/mock")
@ConditionalOnProperty(name = "shadowflow.quant.mock-enabled", havingValue = "true")
public class MockQuantController {

    private final QuantDecisionService decision;
    private final QuantRepository repository;
    private final QuantSnapshotService snapshot;

    public MockQuantController(QuantDecisionService decision, QuantRepository repository,
                               QuantSnapshotService snapshot) {
        this.decision = decision;
        this.repository = repository;
        this.snapshot = snapshot;
    }

    /**
     * 立刻采一轮日内分时点。
     *
     * <p>真机上是每分钟由 {@code QuantTask} 采一次，非交易时段不会跑；
     * 验证分时链路时手动触发一下，采出来的是真实行情算的点。
     */
    @PostMapping("/capture")
    public ApiResponse<Map<String, Object>> capture() {
        int n = snapshot.captureIntraday();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("points", n);
        return ApiResponse.ok(out, "采到 " + n + " 个分时点");
    }

    /**
     * 模拟「次日开盘前」：把可用数量对齐到持仓数量。
     *
     * <p>真机上这一步由 {@code QuantTask.initToday()} 每天开盘前做，验证 T+1 次日可卖时没法等一天，
     * 就手动跑一次同样的动作。
     */
    @PostMapping("/unlock")
    public ApiResponse<Map<String, Object>> unlock(@RequestBody Map<String, Object> body) {
        Object sidRaw = body.get("strategyId");
        if (sidRaw == null) {
            return ApiResponse.fail("需要 strategyId");
        }
        long sid = Long.parseLong(String.valueOf(sidRaw).trim());
        int n = 0;
        for (QuantHolding h : repository.listHoldings(sid)) {
            if (h.getAvailableQty() != h.getQuantity()) {
                h.setAvailableQty(h.getQuantity());
                repository.saveHolding(h);
                n++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("unlocked", n);
        return ApiResponse.ok(out, "放开了 " + n + " 条持仓的可用数量");
    }

    /**
     * 跑一轮 mock 决策。
     *
     * <p>请求体：<code>{"strategyId":1,"code":"600584","name":"长电科技","price":52.5}</code>
     */
    @PostMapping("/run")
    public ApiResponse<Map<String, Object>> run(@RequestBody Map<String, Object> body) {
        Object sidRaw = body.get("strategyId");
        Object codeRaw = body.get("code");
        Object priceRaw = body.get("price");
        if (sidRaw == null || codeRaw == null || priceRaw == null) {
            return ApiResponse.fail("需要 strategyId、code、price");
        }
        long sid = Long.parseLong(String.valueOf(sidRaw).trim());
        String code = String.valueOf(codeRaw).trim();
        String name = body.get("name") == null ? code : String.valueOf(body.get("name")).trim();
        BigDecimal price = new BigDecimal(String.valueOf(priceRaw).trim());

        QuantQuote q = new QuantQuote();
        q.setCode(code);
        q.setName(name);
        q.setPrice(price);
        q.setPreClose(price);
        q.setOpen(price);
        q.setHigh(price);
        q.setLow(price);
        q.setAvgPrice(price);
        q.setChangePct(BigDecimal.ZERO);
        q.setChangeAmt(BigDecimal.ZERO);
        q.setVolume(BigDecimal.valueOf(10_000_000L));
        q.setAmount(price.multiply(BigDecimal.valueOf(10_000_000L)));
        q.setTurnoverRate(BigDecimal.valueOf(3));
        q.setVolumeRatio(BigDecimal.valueOf(1.5));
        q.setMainNetInflow(BigDecimal.valueOf(30_000_000L));

        // 指标直接给「全部通过 + 高分」：这里要验的是判定之后的撮合与落库，不是评分算法
        QuantMetrics m = new QuantMetrics();
        m.setCode(code);
        m.setName(name);
        m.setPrice(price);
        m.setL1Pass(true);
        m.setTotalScore(BigDecimal.valueOf(90));
        m.setL2Score(80);
        m.setAvgScore(80);
        m.setTodayMain(BigDecimal.valueOf(30_000_000L));

        Map<String, QuantQuote> snaps = new HashMap<>();
        snaps.put(code, q);
        Map<String, QuantMetrics> metrics = new HashMap<>();
        metrics.put(code, m);

        int trades = decision.runMock(sid, snaps, metrics);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trades", trades);
        out.put("strategyId", sid);
        out.put("code", code);
        out.put("price", price);
        return ApiResponse.ok(out, trades > 0 ? "已成交 " + trades + " 笔" : "未成交，看日志里的原因");
    }
}
