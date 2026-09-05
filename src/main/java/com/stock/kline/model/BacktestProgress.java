package com.stock.kline.model;

/** 回测执行进度（前端轮询展示） */
public class BacktestProgress {

    /** 校验数据是否齐全 */
    public static final String STAGE_CHECK = "check";
    /** 正在从供应商同步缺失数据 */
    public static final String STAGE_FETCH = "fetch";
    /** 正在逐日计算 */
    public static final String STAGE_RUN = "run";
    /** 已完成 */
    public static final String STAGE_DONE = "done";
    /** 失败 */
    public static final String STAGE_ERROR = "error";

    private String stage = STAGE_CHECK;
    /** 已处理交易日数 */
    private int done;
    /** 需要计算的总交易日数 */
    private int total;
    /** 已处理百分比 = done / total × 100 */
    private int percent;
    private String message;
    /** 最后更新时间戳，用于清理过期进度 */
    private long updateTime = System.currentTimeMillis();

    public BacktestProgress() {
    }

    public BacktestProgress(String stage, int done, int total, String message) {
        this.stage = stage;
        this.done = done;
        this.total = total;
        this.percent = calcPercent(done, total);
        this.message = message;
        this.updateTime = System.currentTimeMillis();
    }

    private static int calcPercent(int done, int total) {
        if (total <= 0) {
            return 0;
        }
        int p = (int) Math.round(done * 100.0 / total);
        return Math.min(100, Math.max(0, p));
    }

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public int getDone() {
        return done;
    }

    public void setDone(int done) {
        this.done = done;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getPercent() {
        return percent;
    }

    public void setPercent(int percent) {
        this.percent = percent;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }
}
