package com.stock.kline.support;

import com.stock.kline.model.PageResult;
import com.stock.kline.model.StockListItem;
import com.stock.kline.service.StockListService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 数据库模式：候选来自 waizao_stock_list */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbStockDirectory implements StockDirectory {

    /**
     * 系统只做沪深A股，市场固定为 stock。
     *
     * <p>库表里还留着 market 列（历史数据），但不作为对外维度暴露。
     */
    private static final String MARKET = "stock";

    private final StockListService service;

    public DbStockDirectory(StockListService service) {
        this.service = service;
    }

    @Override
    public List<Item> search(String keyword, int limit) {
        List<StockListItem> rows = service.search(keyword, MARKET, limit);
        List<Item> out = new ArrayList<>(rows.size());
        for (StockListItem r : rows) {
            out.add(new Item(r.getStockCode(), r.getStockName()));
        }
        return out;
    }

    /** 与页面 /api/stocks 的分页口径一致：按代码升序、关键字匹配代码或名称 */
    @Override
    public PageResult<Item> page(String keyword, int page, int size) {
        PageResult<StockListItem> src = service.page(keyword, MARKET, page <= 0 ? 1 : page, size <= 0 ? 20 : size);
        List<Item> rows = new ArrayList<>(src.getRows().size());
        for (StockListItem r : src.getRows()) {
            rows.add(new Item(r.getStockCode(), r.getStockName()));
        }
        return new PageResult<>(rows, src.getTotal(), src.getPage(), src.getSize());
    }
}
