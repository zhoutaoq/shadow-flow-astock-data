package com.stock.kline.model;

/** 「系统配置」页保存首页背景行情的请求体：标的 + 日期区间 */
public class HomeHeroRequest {

    private String code;
    private String name;
    private String startDate;
    private String endDate;

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
}
