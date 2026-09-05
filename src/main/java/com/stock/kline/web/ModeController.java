package com.stock.kline.web;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.ApiResponse;
import com.stock.kline.support.BacktestStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行模式。
 *
 * <p>前端启动第一件事就是拉它，据此决定显示哪些菜单：
 * 无数据库模式下股票列表、数据更新、调用日志、系统配置这些依赖库的入口全部隐藏。
 */
@RestController
@RequestMapping("/api/mode")
public class ModeController {

    private final WaizaoProperties props;
    private final BacktestStore store;

    public ModeController(WaizaoProperties props, BacktestStore store) {
        this.props = props;
        this.store = store;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> mode() {
        boolean db = props.getDb().isEnabled();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dbEnabled", db);
        data.put("mode", db ? "db" : "nodb");
        data.put("maxSpanDays", props.getMaxSpanDays());
        data.put("backtestPersistent", store.persistent());
        // 无数据库模式下令牌由浏览器保存、每次请求带上，页面要提示用户先去填
        data.put("tokenFromRequest", !db);
        return ApiResponse.ok(data);
    }
}
