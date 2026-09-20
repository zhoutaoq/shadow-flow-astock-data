package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.model.StockListItem;
import io.github.zhoutaoq.shadowflow.biz.repo.StockListRepository;
import io.github.zhoutaoq.shadowflow.biz.service.StockListImportService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 股票列表 shadowflow_stock_list 管理接口 */
@RestController
@RequestMapping("/api/stocks")
public class StockListController {

    /** 系统只关注沪深A股：市场维度不对外暴露，所有查询默认锚在 stock 上 */
    private static final String MARKET = "stock";

    private final StockListRepository repository;
    private final StockListImportService importService;

    public StockListController(StockListRepository repository, StockListImportService importService) {
        this.repository = repository;
        this.importService = importService;
    }

    @GetMapping
    public ApiResponse<PageResult<StockListItem>> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = MARKET) String market,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        if (size <= 0) {
            size = 20;
        }
        if (size > 2000) {
            size = 2000;
        }
        return ApiResponse.ok(repository.page(keyword, market, page, size));
    }

    /**
     * 股票选择器联想：按代码 / 名称模糊匹配，最多返回 limit 条（默认 10，上限 50）。
     *
     * <p>页面上所有"股票"参数都走这里取候选再点选，不允许随手填一个不存在的编号。
     */
    @GetMapping("/search")
    public ApiResponse<List<StockListItem>> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = MARKET) String market,
            @RequestParam(defaultValue = "10") int limit) {
        if (limit <= 0) {
            limit = 10;
        }
        if (limit > 50) {
            limit = 50;
        }
        return ApiResponse.ok(repository.search(keyword, market, limit));
    }

    /** 按筛选条件统计命中只数，供批量抓取前预览规模 */
    @GetMapping("/count")
    public ApiResponse<Map<String, Object>> count(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = MARKET) String market,
            @RequestParam(required = false) Integer enabled) {
        Map<String, Object> data = new HashMap<>();
        data.put("count", repository.countByFilter(keyword, market, enabled));
        return ApiResponse.ok(data);
    }

    /** 不分页全量，供下拉/多选选择器使用 */
    @GetMapping("/all")
    public ApiResponse<List<StockListItem>> all(@RequestParam(defaultValue = MARKET) String market) {
        return ApiResponse.ok(repository.findAll(market));
    }

    @GetMapping("/{id}")
    public ApiResponse<StockListItem> get(@PathVariable Long id) {
        StockListItem item = repository.findById(id);
        if (item == null) {
            return ApiResponse.fail("记录不存在, id=" + id);
        }
        return ApiResponse.ok(item);
    }

    @PostMapping
    public ApiResponse<Long> add(@RequestBody StockListItem item) {
        if (item == null || item.getStockCode() == null || item.getStockCode().trim().isEmpty()) {
            return ApiResponse.fail("股票代码不能为空");
        }
        try {
            long id = repository.insert(item);
            return ApiResponse.ok(id, "新增成功");
        } catch (Exception e) {
            return ApiResponse.fail("新增失败: " + rootMsg(e));
        }
    }

    @PutMapping("/{id}")
    public ApiResponse<Integer> update(@PathVariable Long id, @RequestBody StockListItem item) {
        if (item == null) {
            return ApiResponse.fail("请求体不能为空");
        }
        item.setId(id);
        int rows = repository.update(item);
        if (rows == 0) {
            return ApiResponse.fail("更新失败, 记录不存在 id=" + id);
        }
        return ApiResponse.ok(rows, "更新成功");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Integer> delete(@PathVariable Long id) {
        return ApiResponse.ok(repository.delete(id), "删除成功");
    }

    /** 从 resources/stock-list.json 导入；只导入沪深A股（6 位且 60/68/00/30 开头） */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importJson(
            @RequestBody(required = false) Map<String, String> body) {
        String file = body == null ? null : body.get("file");
        try {
            long before = repository.count();
            int parsed = importService.importFromFile(file);
            Map<String, Object> data = new HashMap<>();
            data.put("parsed", parsed);
            data.put("before", before);
            data.put("after", repository.count());
            return ApiResponse.ok(data, "导入完成，解析 " + parsed + " 条");
        } catch (Exception e) {
            return ApiResponse.fail("导入失败: " + rootMsg(e));
        }
    }

    public static String rootMsg(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.getClass().getSimpleName() : m;
    }
}
