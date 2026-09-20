package io.github.zhoutaoq.shadowflow.biz.service;

import io.github.zhoutaoq.shadowflow.biz.config.WaizaoProperties;
import io.github.zhoutaoq.shadowflow.biz.model.ApiCallLog;
import io.github.zhoutaoq.shadowflow.biz.model.FetchJob;
import io.github.zhoutaoq.shadowflow.biz.repo.StockListRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 批处理抓取作业：按筛选条件解析股票清单 → 多线程抓取 → 落库。
 *
 * <p>作业状态存内存（重启即失），够用于人工触发的批处理场景。
 * 每只股票在整个日期区间内只发一次请求。
 */
@Service
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class KlineFetchJobService {

    private static final Logger log = LoggerFactory.getLogger(KlineFetchJobService.class);

    private final KlineFetchService fetchService;
    private final StockListRepository stockListRepository;
    private final WaizaoProperties props;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, FetchJob> jobs = new ConcurrentHashMap<>();
    /** 内存中最多保留的作业数，超出后淘汰最早的，避免长时间运行导致内存无限增长 */
    private static final int MAX_JOBS = 200;

    public KlineFetchJobService(KlineFetchService fetchService,
                                StockListRepository stockListRepository,
                                WaizaoProperties props) {
        this.fetchService = fetchService;
        this.stockListRepository = stockListRepository;
        this.props = props;
        this.executor = buildExecutor(props.getApi().getFetchConcurrency());
    }

    private static ThreadPoolTaskExecutor buildExecutor(int concurrency) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(concurrency);
        ex.setMaxPoolSize(Math.max(concurrency, 32));
        ex.setQueueCapacity(100000);
        ex.setThreadNamePrefix("kline-fetch-");
        ex.setWaitForTasksToCompleteOnShutdown(false);
        ex.initialize();
        return ex;
    }

    /** 提交作业（异步执行），返回 jobId */
    public FetchJob submit(String market, String keyword, Integer enabled,
                           String startDate, String endDate, boolean dryRun,
                           Integer concurrency, int limit) {
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("开始日期和结束日期不能为空");
        }
        if (startDate.compareTo(endDate) > 0) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }

        List<String> codes = stockListRepository.findCodesByFilter(keyword, market, enabled, limit);
        if (codes.isEmpty()) {
            throw new IllegalArgumentException("筛选条件未命中任何股票");
        }

        FetchJob job = new FetchJob();
        job.setJobId(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        job.setMarket(market == null ? "stock" : market);
        job.setKeyword(keyword);
        job.setEnabled(enabled);
        job.setStartDate(startDate);
        job.setEndDate(endDate);
        job.setDryRun(dryRun);
        job.setConcurrency(concurrency == null || concurrency <= 0
                ? props.getApi().getFetchConcurrency() : concurrency);
        job.setTotal(codes.size());
        job.setStartedAt(System.currentTimeMillis());
        job.setStatus(FetchJob.Status.RUNNING);
        // 并发度取自配置文件，改了重启后生效
        executor.setCorePoolSize(job.getConcurrency());
        executor.setMaxPoolSize(Math.max(job.getConcurrency(), 32));
        jobs.put(job.getJobId(), job);
        evictIfNeeded();

        log.info("[job] submit jobId={} codes={} range=[{}~{}] dryRun={} concurrency={}",
                job.getJobId(), codes.size(), startDate, endDate, dryRun, job.getConcurrency());
        startWorkers(job, codes);
        return job;
    }

    /**
     * 起固定数量（=concurrency）的工作线程，共享一个游标从清单里取股票。
     *
     * <p>注意：不能在这里阻塞等待工作线程结束——本方法若运行在线程池线程上，
     * 等待会导致池内线程被占满、排队任务永远拿不到线程而死锁。改为由最后一个
     * 结束的工作线程负责收尾。
     */
    /** 作业数超上限时，淘汰最早提交的作业（只淘汰已结束的，避免运行中作业进度查不到） */
    private void evictIfNeeded() {
        if (jobs.size() <= MAX_JOBS) {
            return;
        }
        jobs.entrySet().stream()
                .filter(e -> e.getValue().getStatus() != FetchJob.Status.RUNNING
                        && e.getValue().getStatus() != FetchJob.Status.PENDING)
                .sorted(Comparator.comparingLong(e -> e.getValue().getStartedAt()))
                .limit(Math.max(1, jobs.size() - MAX_JOBS))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList())
                .forEach(jobs::remove);
    }

    private void startWorkers(FetchJob job, List<String> codes) {
        int workers = Math.min(job.getConcurrency(), codes.size());
        AtomicInteger cursor = new AtomicInteger(0);
        AtomicInteger activeWorkers = new AtomicInteger(workers);
        long interval = props.getApi().getFetchIntervalMs();

        for (int w = 0; w < workers; w++) {
            executor.execute(() -> {
                try {
                    while (true) {
                        if (job.isCancelRequested()) {
                            return;
                        }
                        int idx = cursor.getAndIncrement();
                        if (idx >= codes.size()) {
                            return;
                        }
                        process(job, codes.get(idx));
                        if (interval > 0) {
                            sleepQuietly(interval);
                        }
                    }
                } finally {
                    if (activeWorkers.decrementAndGet() == 0) {
                        finish(job);
                    }
                }
            });
        }
    }

    private void process(FetchJob job, String code) {
        boolean ok;
        String errMsg = null;
        int fetched = 0;
        int persisted = 0;
        try {
            KlineFetchService.CodeResult cr = fetchService.fetchOne(
                    job.getMarket(), code, job.getStartDate(), job.getEndDate(), job.isDryRun(),
                    ApiCallLog.SCENE_BATCH);
            fetched = cr.getFetched();
            persisted = cr.getPersisted();
            ok = cr.isSuccess();
            errMsg = cr.getMessage();
        } catch (Throwable t) {
            ok = false;
            errMsg = t.getMessage();
        }

        synchronized (job) {
            job.setCompleted(job.getCompleted() + 1);
            job.setFetchedTotal(job.getFetchedTotal() + fetched);
            job.setPersistedTotal(job.getPersistedTotal() + persisted);
            if (ok) {
                job.setSuccessCount(job.getSuccessCount() + 1);
            } else {
                job.setFailedCount(job.getFailedCount() + 1);
                if (job.getErrors().size() < 200) {
                    job.getErrors().add(new FetchJob.ErrorItem(code, errMsg));
                }
            }
        }
    }

    private void finish(FetchJob job) {
        job.setFinishedAt(System.currentTimeMillis());
        job.setStatus(job.isCancelRequested() ? FetchJob.Status.CANCELLED : FetchJob.Status.DONE);
        log.info("[job] finished jobId={} total={} success={} failed={} fetched={} persisted={} elapsed={}ms",
                job.getJobId(), job.getTotal(), job.getSuccessCount(), job.getFailedCount(),
                job.getFetchedTotal(), job.getPersistedTotal(), job.getElapsedMs());
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public FetchJob get(String jobId) {
        return jobs.get(jobId);
    }

    /** 请求取消：已经在跑的请求不会中断，只是不再处理剩余股票 */
    public boolean cancel(String jobId) {
        FetchJob job = jobs.get(jobId);
        if (job == null) {
            return false;
        }
        job.setCancelRequested(true);
        return true;
    }
}
