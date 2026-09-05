package com.stock.kline.model;

/** waizao_api_call_log 接口调用日志（只记状态码，不记响应体） */
public class ApiCallLog {

    /** 调用场景-调试：调试页发起 */
    public static final String SCENE_DEV = "dev";
    /** 调用场景-批处理：单股票 / 多股票 / 筛选批量抓取 */
    public static final String SCENE_BATCH = "batch";

    private Long id;
    /** 调用场景，见 SCENE_DEV / SCENE_BATCH */
    private String scene = SCENE_BATCH;
    private String stockCode;
    private String market;
    private String reqStartDate;
    private String reqEndDate;
    /** 请求发生时间，格式 yyyy-MM-dd HH:mm:ss */
    private String requestTime;
    private int httpStatus;
    private int costMs;
    private int rowCount;
    private Integer success;
    private String errorMsg;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScene() {
        return scene;
    }

    public void setScene(String scene) {
        this.scene = scene;
    }

    public String getStockCode() {
        return stockCode;
    }

    public void setStockCode(String stockCode) {
        this.stockCode = stockCode;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getReqStartDate() {
        return reqStartDate;
    }

    public void setReqStartDate(String reqStartDate) {
        this.reqStartDate = reqStartDate;
    }

    public String getReqEndDate() {
        return reqEndDate;
    }

    public void setReqEndDate(String reqEndDate) {
        this.reqEndDate = reqEndDate;
    }

    public String getRequestTime() {
        return requestTime;
    }

    public void setRequestTime(String requestTime) {
        this.requestTime = requestTime;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int getCostMs() {
        return costMs;
    }

    public void setCostMs(int costMs) {
        this.costMs = costMs;
    }

    public int getRowCount() {
        return rowCount;
    }

    public void setRowCount(int rowCount) {
        this.rowCount = rowCount;
    }

    public Integer getSuccess() {
        return success;
    }

    public void setSuccess(Integer success) {
        this.success = success;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }
}
