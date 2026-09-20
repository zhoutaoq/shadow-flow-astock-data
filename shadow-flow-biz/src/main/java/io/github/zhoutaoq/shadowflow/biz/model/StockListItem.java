package io.github.zhoutaoq.shadowflow.biz.model;

import java.math.BigDecimal;

/** waizao_stock_list 股票列表 */
public class StockListItem {

    private Long id;
    private String stockCode;
    private String stockName;
    private String market = "stock";
    private Integer enabled = 1;
    private String createTime;
    private String updateTime;

    public StockListItem() {
    }

    public StockListItem(String stockCode, String stockName, String market) {
        this.stockCode = stockCode;
        this.stockName = stockName;
        this.market = market;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getStockName() {
        return stockName;
    }

    public void setStockName(String stockName) {
        this.stockName = stockName;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public String getCreateTime() {
        return createTime;
    }

    public void setCreateTime(String createTime) {
        this.createTime = createTime;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }
}
