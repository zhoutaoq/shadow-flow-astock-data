package com.stock.kline.web;

import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.ApiResponse;
import com.stock.kline.repo.ApiCallLogRepository;
import com.stock.kline.repo.BacktestRepository;
import com.stock.kline.store.DataDir;
import com.stock.kline.store.StorageMode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行模式与持久化策略。
 *
 * <p>前端启动第一件事就是拉它，据此决定显示哪些菜单。
 *
 * <p>v1.3 之后「有没有数据库」不再等于「能不能存数据」：
 * 无数据库模式下配置、股票列表、回测照样落 {@code ./data}，
 * 只有真正依赖库表的入口（K线落库管理、调用日志）才隐藏。
 */
@RestController
@RequestMapping("/api/mode")
public class ModeController {

    private final WaizaoProperties props;
    private final BacktestRepository backtestRepository;
    private final ApiCallLogRepository callLogRepository;
    private final DataDir dataDir;

    public ModeController(WaizaoProperties props, BacktestRepository backtestRepository,
                          ApiCallLogRepository callLogRepository, DataDir dataDir) {
        this.props = props;
        this.backtestRepository = backtestRepository;
        this.callLogRepository = callLogRepository;
        this.dataDir = dataDir;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> mode() {
        boolean db = props.getDb().isEnabled();
        StorageMode storage = db ? StorageMode.DB : StorageMode.FILE;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dbEnabled", db);
        data.put("mode", db ? "db" : "nodb");
        // 持久化策略：db = 落 MySQL；file = 落 ./data 数据文件
        data.put("storageMode", storage.getCode());
        data.put("storageLabel", storage.getLabel());
        data.put("dataDir", dataDir.root().toAbsolutePath().normalize().toString());
        data.put("maxSpanDays", props.getMaxSpanDays());
        data.put("backtestPersistent", backtestRepository.persistent());
        data.put("callLogPersistent", callLogRepository.persistent());
        return ApiResponse.ok(data);
    }
}
