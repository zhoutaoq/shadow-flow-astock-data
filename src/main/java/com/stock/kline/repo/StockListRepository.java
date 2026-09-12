package com.stock.kline.repo;

import com.stock.kline.model.PageResult;
import com.stock.kline.model.StockListItem;

import java.util.List;

/**
 * 股票名录的统一仓储。
 *
 * <p>v1.3：两种模式下一套接口、一套模型 ——
 * 数据库模式写 {@code waizao_stock_list}，无数据库模式写 {@code ./data/stock-list.json}
 * （首次启动用 {@code resources/stock-list.json} 播种，之后以落盘文件为准）。
 *
 * <p>系统只做沪深A股，{@code market} 默认固定为 {@code stock}。
 */
public interface StockListRepository {

    PageResult<StockListItem> page(String keyword, String market, int page, int size);

    /**
     * 按代码 / 名称模糊匹配，供页面股票选择器联想。
     *
     * <p>排序：完全相等 → 代码前缀 → 其余，同档内按代码升序。
     */
    List<StockListItem> search(String keyword, String market, int limit);

    List<StockListItem> findAll(String market);

    List<StockListItem> findByCodes(List<String> codes, String market);

    StockListItem findById(Long id);

    /** 新增，返回主键 */
    long insert(StockListItem item);

    int update(StockListItem item);

    int delete(Long id);

    /** 按筛选条件统计命中只数（批量抓取前预览规模） */
    long countByFilter(String keyword, String market, Integer enabled);

    /**
     * 按筛选条件解析出股票代码列表。
     *
     * @param limit 最多返回多少只，防止误操作把全表拉起跑
     */
    List<String> findCodesByFilter(String keyword, String market, Integer enabled, int limit);

    /** 批量 upsert 导入 */
    int upsertBatch(List<StockListItem> items);

    long count();

    /** 市场缺省值 */
    default String defaultMarket(String market) {
        return (market == null || market.trim().isEmpty()) ? "stock" : market.trim();
    }
}
