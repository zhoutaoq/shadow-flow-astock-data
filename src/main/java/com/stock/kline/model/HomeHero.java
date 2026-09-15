package com.stock.kline.model;

import java.util.List;

/**
 * 首页品牌带背景行情：一段真实日K（标的 + 日期区间 + 逐日行情）。
 *
 * <p>默认取的是一只票的一段历史走势（见 {@code HomeHeroService} 的默认值），
 * 超管可以在「系统配置」页把它换成任意标的与区间。
 *
 * <p>{@link #configured} 为 false 表示还没配置过（配置行不存在），
 * 这时 {@link #bars} 为空，首页用它自带的那段数据渲染 —— 免得首页为了画个背景
 * 还得先跑一次取数。
 *
 * <p>{@link #bars} 每行是 {@code [开, 高, 低, 收, 成交量]}，与前端的绘制口径一一对应。
 */
public class HomeHero {

    /** 是否已有配置（false = 仍在用页面内置的默认背景） */
    private boolean configured;
    private String code;
    private String name;
    private String startDate;
    private String endDate;
    /** 这段配置最后一次保存的时间 */
    private String updateTime;
    /** [开, 高, 低, 收, 成交量]，按交易日升序；取不到数据时为空 */
    private List<double[]> bars;

    public boolean isConfigured() {
        return configured;
    }

    public void setConfigured(boolean configured) {
        this.configured = configured;
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

    public String getStartDate() {
        return startDate;
    }

    public void setStartDate(String startDate) {
        this.startDate = startDate;
    }

    public String getEndDate() {
        return endDate;
    }

    public void setEndDate(String endDate) {
        this.endDate = endDate;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public List<double[]> getBars() {
        return bars;
    }

    public void setBars(List<double[]> bars) {
        this.bars = bars;
    }
}
