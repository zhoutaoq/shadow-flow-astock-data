package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.model.StockKlineDaily;
import io.github.zhoutaoq.shadowflow.biz.service.StockKlineService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 日K线 shadowflow_stock_kline_daily 管理接口 */
@RestController
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "true")
@RequestMapping("/api/klines")
public class StockKlineController {

    private final StockKlineService service;

    public StockKlineController(StockKlineService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResult<StockKlineDaily>> page(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size <= 0) {
            size = 20;
        }
        if (size > 5000) {
            size = 5000;
        }
        return ApiResponse.ok(service.page(code, market, startDate, endDate, page, size));
    }

    /** 按业务主键（代码 + 交易日）查一条 */
    @GetMapping("/key")
    public ApiResponse<StockKlineDaily> getByKey(
            @RequestParam String code,
            @RequestParam(defaultValue = "stock") String market,
            @RequestParam String tradeDate) {
        StockKlineDaily k = service.findByKey(code, market, tradeDate);
        if (k == null) {
            return ApiResponse.fail("记录不存在, code=" + code + ", tradeDate=" + tradeDate);
        }
        return ApiResponse.ok(k);
    }

    /** 新增（已存在同代码 + 同交易日则覆盖更新） */
    @PostMapping
    public ApiResponse<Integer> add(@RequestBody StockKlineDaily k) {
        if (k == null || k.getStockCode() == null || k.getTradeDate() == null) {
            return ApiResponse.fail("股票代码和交易日不能为空");
        }
        try {
            return ApiResponse.ok(service.upsertOne(k), "保存成功");
        } catch (Exception e) {
            return ApiResponse.fail("保存失败: " + StockListController.rootMsg(e));
        }
    }

    /** 按业务主键更新：代码与交易日为定位键，只能改价格字段 */
    @PutMapping("/key")
    public ApiResponse<Integer> update(@RequestBody StockKlineDaily k) {
        if (k == null || k.getStockCode() == null || k.getTradeDate() == null) {
            return ApiResponse.fail("股票代码和交易日不能为空");
        }
        int rows = service.update(k);
        if (rows == 0) {
            return ApiResponse.fail("更新失败, 记录不存在 code=" + k.getStockCode() + " tradeDate=" + k.getTradeDate());
        }
        return ApiResponse.ok(rows, "更新成功");
    }

    /** 按业务主键删除一条 */
    @DeleteMapping("/key")
    public ApiResponse<Integer> delete(
            @RequestParam String code,
            @RequestParam(defaultValue = "stock") String market,
            @RequestParam String tradeDate) {
        return ApiResponse.ok(service.delete(code, market, tradeDate), "删除成功");
    }

    /** 按条件批量删除（清空某股票/某区间） */
    @DeleteMapping("/batch")
    public ApiResponse<Integer> deleteBatch(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        if (code == null && startDate == null && endDate == null) {
            return ApiResponse.fail("请至少指定股票代码或日期范围，避免误删全表");
        }
        return ApiResponse.ok(service.deleteByRange(code, market, startDate, endDate), "删除完成");
    }

    /** 某只股票日K的日期跨度（分析图懒加载用） */
    @GetMapping("/span")
    public ApiResponse<Map<String, Object>> span(
            @RequestParam String code,
            @RequestParam(required = false) String market) {
        return ApiResponse.ok(service.dateSpan(code, market));
    }

    /** K 线图数据 */
    @GetMapping("/chart")
    public ApiResponse<Map<String, Object>> chart(
            @RequestParam String code,
            @RequestParam(required = false) String market,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        List<StockKlineDaily> rows = service.listForChart(code, market, startDate, endDate);
        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        data.put("market", market == null ? "stock" : market);
        data.put("startDate", startDate);
        data.put("endDate", endDate);
        data.put("rows", rows);
        return ApiResponse.ok(data);
    }

    /** 每只股票已有 K 线条数，用于落盘概览 */
    @GetMapping("/coverage")
    public ApiResponse<Map<String, Long>> coverage() {
        return ApiResponse.ok(service.countGroupByCode());
    }
}
