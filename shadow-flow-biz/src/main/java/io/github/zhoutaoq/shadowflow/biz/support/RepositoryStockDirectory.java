package io.github.zhoutaoq.shadowflow.biz.support;

import io.github.zhoutaoq.shadowflow.biz.model.PageResult;
import io.github.zhoutaoq.shadowflow.biz.model.StockListItem;
import io.github.zhoutaoq.shadowflow.biz.repo.StockListRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 股票名录：两种模式下都从 {@link StockListRepository} 取候选。
 *
 * <p>v1.3 之前这里有两套实现（数据库查 shadowflow_stock_list、无数据库读本地 json），
 * 现在仓储层已经把两种落点统一了，名录只剩这一份代码：
 * 数据库模式查库，无数据库模式查 {@code ./data/stock-list.json}。
 *
 * <p>系统只做沪深A股，所以名录没有市场维度，市场固定为 stock。
 */
@Component
public class RepositoryStockDirectory implements StockDirectory {

    /** 系统只关注沪深A股，市场固定为 stock */
    private static final String MARKET = "stock";

    private final StockListRepository repository;

    public RepositoryStockDirectory(StockListRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Item> search(String keyword, int limit) {
        List<StockListItem> rows = repository.search(keyword, MARKET, limit);
        List<Item> out = new ArrayList<>(rows.size());
        for (StockListItem r : rows) {
            out.add(new Item(r.getStockCode(), r.getStockName()));
        }
        return out;
    }

    @Override
    public PageResult<Item> page(String keyword, int page, int size) {
        PageResult<StockListItem> src = repository.page(keyword, MARKET, page <= 0 ? 1 : page,
                size <= 0 ? 20 : size);
        List<Item> rows = new ArrayList<>(src.getRows().size());
        for (StockListItem r : src.getRows()) {
            rows.add(new Item(r.getStockCode(), r.getStockName()));
        }
        return new PageResult<>(rows, src.getTotal(), src.getPage(), src.getSize());
    }
}
