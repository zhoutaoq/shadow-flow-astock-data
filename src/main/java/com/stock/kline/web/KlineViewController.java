package com.stock.kline.web;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.ApiResponse;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.repo.KlineRepository;
import com.stock.kline.support.KlineWindow;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * K线查看：两种运行模式下都能用。
 *
 * <p>与 /api/klines 的区别：后者是日K表的增删改查，只在数据库模式下注册；
 * 这里只做只读的看图取数，数据库模式读表，无数据库模式向供应商现取。
 */
@RestController
@RequestMapping("/api/kline")
public class KlineViewController {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 系统只做沪深A股，供应商的 symbol 参数恒定是这个值 */
    private static final String MARKET = "stock";

    private final KlineRepository klineRepository;
    private final WaizaoProperties props;

    public KlineViewController(KlineRepository klineRepository, WaizaoProperties props) {
        this.klineRepository = klineRepository;
        this.props = props;
    }

    /** K线图数据（按交易日升序） */
    @GetMapping("/chart")
    public ApiResponse<Map<String, Object>> chart(
            @RequestParam String code,
            @RequestParam String startDate,
            @RequestParam String endDate) {
        if (!StringUtils.hasText(startDate) || !StringUtils.hasText(endDate)) {
            return ApiResponse.fail("请指定起止日期");
        }
        String start = startDate.trim();
        String end = endDate.trim();
        if (start.compareTo(end) > 0) {
            return ApiResponse.fail("起始日期不能晚于结束日期");
        }
        // 无数据库模式每次都是一次真实请求，跨度先卡住再发
        if (!props.getDb().isEnabled()) {
            long span = ChronoUnit.DAYS.between(LocalDate.parse(start, DF), LocalDate.parse(end, DF)) + 1;
            if (span > props.getMaxSpanDays()) {
                return ApiResponse.fail("无数据库模式单次查询最多 " + props.getMaxSpanDays()
                        + " 个自然日，当前 " + span + " 天");
            }
        }
        KlineWindow w = klineRepository.load(MARKET, code.trim(), start, end, 0);
        List<StockKlineDaily> rows = w.getRows();
        Map<String, Object> data = new HashMap<>();
        data.put("code", code);
        data.put("startDate", start);
        data.put("endDate", end);
        data.put("rows", rows);
        return ApiResponse.ok(data);
    }

    /** 可查范围的日期跨度；无数据库模式返回 count=0，页面改由用户给起止日期 */
    @GetMapping("/span")
    public ApiResponse<Map<String, Object>> span(@RequestParam String code) {
        return ApiResponse.ok(klineRepository.span(MARKET, code.trim()));
    }
}
