package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.support.StockDirectory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 股票名录查询：给页面的股票选择器提供候选。
 *
 * <p>与 /api/stocks 的区别：这个接口两种运行模式下都在 ——
 * 数据库模式查 waizao_stock_list，无数据库模式读本地 stock-list.json。
 */
@RestController
@RequestMapping("/api/dir")
public class StockDirectoryController {

    private final StockDirectory directory;

    public StockDirectoryController(StockDirectory directory) {
        this.directory = directory;
    }

    @GetMapping("/stocks")
    public ApiResponse<List<StockDirectory.Item>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "10") int limit) {
        if (limit <= 0) {
            limit = 10;
        }
        if (limit > 50) {
            limit = 50;
        }
        return ApiResponse.ok(directory.search(keyword, limit));
    }

    /**
     * 名录分页：无数据库模式的股票列表页走这里。
     *
     * <p>与 /api/stocks 的区别同 {@link #search} —— 这个接口两种模式下都在，
     * 无数据库模式翻的是内存里的 stock-list.json。
     */
    @GetMapping("/stocks/page")
    public ApiResponse<PageResult<StockDirectory.Item>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        int p = page <= 0 ? 1 : page;
        int s = size <= 0 ? 20 : Math.min(size, 200);
        return ApiResponse.ok(directory.page(keyword, p, s));
    }
}
