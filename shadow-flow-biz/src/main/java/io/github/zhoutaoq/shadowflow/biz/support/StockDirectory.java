package io.github.zhoutaoq.shadowflow.biz.support;

import io.github.zhoutaoq.shadowflow.biz.model.PageResult;

import java.util.List;

/**
 * 股票名录：给页面的股票选择器与股票列表提供候选。
 *
 * <p>系统只做沪深A股，所以名录没有市场维度 —— 数据库模式查 waizao_stock_list 的 stock 分类，
 * 无数据库模式读本地 resources/stock-list.json 里的沪深A股代码。
 *
 * <p>两种实现：
 * <ul>
 *   <li>数据库模式：查 waizao_stock_list</li>
 *   <li>无数据库模式：读本地 resources/stock-list.json</li>
 * </ul>
 */
public interface StockDirectory {

    /**
     * 按代码 / 名称模糊匹配；完全等于关键字的排最前，其次代码前缀匹配。
     *
     * <p>匹配一律在服务端做：名录几千条，全量下发给前端再过滤既慢也没必要，
     * 前端只需要在输入停顿后带着关键字来问一次。
     */
    List<Item> search(String keyword, int limit);

    /**
     * 分页查询名录，两种模式下都要能用（无数据库模式的股票列表页直接翻这份数据）。
     *
     * @param page 第几页，从 1 开始
     */
    PageResult<Item> page(String keyword, int page, int size);

    /** 名录条目 */
    class Item {
        private String code;
        private String name;

        public Item() {
        }

        public Item(String code, String name) {
            this.code = code;
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
