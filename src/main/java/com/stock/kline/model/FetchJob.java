package com.stock.kline.model;

import java.util.ArrayList;
import java.util.List;

/** 批处理抓取作业：按筛选条件解析出股票清单，多线程抓取落库 */
public class FetchJob {

    public enum Status {
        PENDING, RUNNING, DONE, CANCELLED, FAILED
    }

    public static class ErrorItem {
        private String code;
        private String message;

        public ErrorItem() {
        }

        public ErrorItem(String code, String message) {
            this.code = code;
            this.message = message;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    private String jobId;
    private volatile Status status = Status.PENDING;
    private boolean dryRun;
    private String market;
    private String keyword;
    private Integer enabled;
    private String startDate;
    private String endDate;
    private int concurrency;

    private volatile int total;
    private volatile int completed;
    private volatile int successCount;
    private volatile int failedCount;
    private volatile int fetchedTotal;
    private volatile int persistedTotal;
    private volatile boolean cancelRequested;

    private long startedAt;
    private Long finishedAt;
    private String message;
    private List<ErrorItem> errors = new ArrayList<>();

    /** 完成百分比，整数 */
    public int getProgress() {
        return total <= 0 ? 0 : (int) Math.round(completed * 100.0 / total);
    }

    /** 已耗时（毫秒） */
    public long getElapsedMs() {
        if (startedAt <= 0) {
            return 0;
        }
        return (finishedAt != null ? finishedAt : System.currentTimeMillis()) - startedAt;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public String getKeyword() {
        return keyword;
    }

    public void setKeyword(String keyword) {
        this.keyword = keyword;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
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

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public int getCompleted() {
        return completed;
    }

    public void setCompleted(int completed) {
        this.completed = completed;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public void setFailedCount(int failedCount) {
        this.failedCount = failedCount;
    }

    public int getFetchedTotal() {
        return fetchedTotal;
    }

    public void setFetchedTotal(int fetchedTotal) {
        this.fetchedTotal = fetchedTotal;
    }

    public int getPersistedTotal() {
        return persistedTotal;
    }

    public void setPersistedTotal(int persistedTotal) {
        this.persistedTotal = persistedTotal;
    }

    public boolean isCancelRequested() {
        return cancelRequested;
    }

    public void setCancelRequested(boolean cancelRequested) {
        this.cancelRequested = cancelRequested;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public Long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Long finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ErrorItem> getErrors() {
        return errors;
    }

    public void setErrors(List<ErrorItem> errors) {
        this.errors = errors;
    }
}
