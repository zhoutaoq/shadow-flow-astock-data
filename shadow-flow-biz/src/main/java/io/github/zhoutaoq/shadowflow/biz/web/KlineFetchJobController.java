package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.model.FetchJob;
import io.github.zhoutaoq.shadowflow.biz.service.KlineFetchJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 批处理抓取作业：对股票列表筛选后的结果，按指定日期范围多线程抓取落库。
 */
@RestController
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
@RequestMapping("/api/fetch/jobs")
public class KlineFetchJobController {

    private final KlineFetchJobService jobService;

    public KlineFetchJobController(KlineFetchJobService jobService) {
        this.jobService = jobService;
    }

    /** 提交作业 */
    @PostMapping
    public ApiResponse<FetchJob> submit(@RequestBody JobRequest req) {
        if (req == null) {
            return ApiResponse.fail("请求体不能为空");
        }
        try {
            FetchJob job = jobService.submit(
                    req.getMarket() == null ? "stock" : req.getMarket(),
                    req.getKeyword(),
                    req.getEnabled(),
                    req.getStartDate(),
                    req.getEndDate(),
                    req.isDryRun(),
                    req.getConcurrency(),
                    req.getLimit() == null ? 0 : req.getLimit());
            return ApiResponse.ok(job, "作业已提交，共 " + job.getTotal() + " 只股票");
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("提交失败: " + e.getMessage());
        }
    }

    /** 查询作业进度 */
    @GetMapping("/{jobId}")
    public ApiResponse<FetchJob> get(@PathVariable String jobId) {
        FetchJob job = jobService.get(jobId);
        if (job == null) {
            return ApiResponse.fail("作业不存在: " + jobId);
        }
        return ApiResponse.ok(job);
    }

    /** 取消作业：已在途的请求不中断，剩余股票不再处理 */
    @PostMapping("/{jobId}/cancel")
    public ApiResponse<Boolean> cancel(@PathVariable String jobId) {
        if (jobService.get(jobId) == null) {
            return ApiResponse.fail("作业不存在: " + jobId);
        }
        return ApiResponse.ok(jobService.cancel(jobId), "已发送取消信号");
    }

    public static class JobRequest {
        private String market = "stock";
        private String keyword;
        private Integer enabled;
        private String startDate;
        private String endDate;
        private boolean dryRun = false;
        private Integer concurrency;
        private Integer limit;

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

        public boolean isDryRun() {
            return dryRun;
        }

        public void setDryRun(boolean dryRun) {
            this.dryRun = dryRun;
        }

        public Integer getConcurrency() {
            return concurrency;
        }

        public void setConcurrency(Integer concurrency) {
            this.concurrency = concurrency;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }
    }
}
