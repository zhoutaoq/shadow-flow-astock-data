package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 盯盘的调度器：把「多久跑一轮」从注解里搬到运行时。
 *
 * <p>原来用 <code>@Scheduled(fixedDelayString)</code>，间隔在启动那一刻就定死了，
 * 页面上改间隔只能重启才生效。这里改成自己持有一个单线程调度器，
 * 配置一变就取消旧的定时任务、按新间隔重新排，改完立刻生效。
 *
 * <p>盯盘开关关掉时直接不排任务；再打开时重新排上。
 */
@Component
@ConditionalOnProperty(name = "watch.enabled", havingValue = "true", matchIfMissing = true)
public class WatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WatchScheduler.class);

    private final WatchConfigStore store;
    private final PriceWatchTask task;
    private final ScheduledExecutorService exec;

    private volatile ScheduledFuture<?> future;

    public WatchScheduler(WatchConfigStore store, PriceWatchTask task) {
        this.store = store;
        this.task = task;
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "watch-sched-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        // 单线程：一轮没跑完就不会开始下一轮，慢的时候不叠加
        this.exec = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @PostConstruct
    public void start() {
        store.addListener(settings -> apply());
        apply();
    }

    @PreDestroy
    public void stop() {
        cancel();
        exec.shutdownNow();
    }

    /** 按当前配置重排定时任务 */
    public synchronized void apply() {
        cancel();
        if (!store.isEnabled()) {
            log.info("[watch] 盯盘开关已关闭，停止调度");
            return;
        }
        long seconds = Math.max(1, store.getIntervalSeconds());
        future = exec.scheduleWithFixedDelay(() -> {
            try {
                task.runOnce();
            } catch (Exception e) {
                log.warn("[watch] 盯盘执行异常: {}", e.getMessage());
            }
        }, seconds, seconds, TimeUnit.SECONDS);
        log.info("[watch] 盯盘调度已生效：每 {} 秒一轮", seconds);
    }

    private void cancel() {
        if (future != null) {
            future.cancel(false);
            future = null;
        }
    }
}
