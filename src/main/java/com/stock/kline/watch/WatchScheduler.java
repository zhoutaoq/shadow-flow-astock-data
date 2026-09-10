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
 * 间隔一变就取消旧的定时任务、按新间隔重新排，改完立刻生效。
 *
 * <p>盯盘开关关掉时直接不排任务；再打开时重新排上。
 *
 * <p><b>关键约束：只有「开关」和「间隔」真的变了才重排定时任务。</b>
 * 名单 / 规则 / 钉钉这些每轮都会从 {@link WatchConfigStore} 现读，改它们不影响本轮节奏。
 *
 * <p>早先这里是无条件 {@code cancel() + 重排}，于是每保存一次配置都会把下一轮的倒计时
 * 清零 —— {@code scheduleWithFixedDelay} 的首次延迟是整个 interval，页面上连点几次「保存」，
 * 下一轮就被硬生生往后推 N 个间隔。线上实测：7s 间隔下，10:20:30/35/40/47 连存 4 次，
 * 本该在 :33/:40/:47 跑的 3 轮被吃掉，两轮之间出现 24.4s 空档。
 */
@Component
@ConditionalOnProperty(name = "watch.enabled", havingValue = "true", matchIfMissing = true)
public class WatchScheduler {

    private static final Logger log = LoggerFactory.getLogger(WatchScheduler.class);

    private final WatchConfigStore store;
    private final PriceWatchTask task;
    private final ScheduledExecutorService exec;

    private volatile ScheduledFuture<?> future;
    /** 已排进定时任务的开关状态，用来判断这次配置变更要不要重排 */
    private volatile boolean appliedEnabled;
    /** 已排进定时任务的间隔；-1 表示还没排过 */
    private volatile long appliedInterval = -1;
    /** 是否已经排过至少一次（首次 apply 必须真的排） */
    private volatile boolean applied;

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

    /**
     * 按当前配置重排定时任务。
     *
     * <p>只有开关或间隔变化时才动定时器；名单 / 规则 / 钉钉的变化由每轮现读兜住，
     * 不重排才能保证「保存配置」不会打断正在走的这一轮节奏。
     */
    public synchronized void apply() {
        boolean enabled = store.isEnabled();
        long seconds = Math.max(1, store.getIntervalSeconds());

        if (applied && enabled == appliedEnabled && seconds == appliedInterval) {
            log.debug("[watch] 配置变更与调度无关（间隔仍为 {}s），保持当前节奏", seconds);
            return;
        }

        cancel();
        applied = true;
        appliedEnabled = enabled;
        appliedInterval = seconds;

        if (!enabled) {
            log.info("[watch] 盯盘开关已关闭，停止调度");
            return;
        }
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
