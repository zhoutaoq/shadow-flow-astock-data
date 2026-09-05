package com.stock.kline.support;

import com.stock.kline.model.StockKlineDaily;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次取数的结果：预热段 + 回测区间拼成的一条正序序列。
 */
public class KlineWindow {

    private final List<StockKlineDaily> rows = new ArrayList<>();
    /** 前多少行属于预热段（交易日早于回测起始日） */
    private int preSize;

    public List<StockKlineDaily> getRows() {
        return rows;
    }

    public int getPreSize() {
        return preSize;
    }

    public void setPreSize(int preSize) {
        this.preSize = preSize;
    }

    /** 回测区间内的数据（去掉预热段） */
    public List<StockKlineDaily> rangeRows() {
        return new ArrayList<>(rows.subList(Math.min(preSize, rows.size()), rows.size()));
    }
}
