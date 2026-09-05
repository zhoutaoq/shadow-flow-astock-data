package com.stock.kline.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import javax.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时盯盘提醒的装配。
 *
 * <p>整块功能由 <code>watch.enabled</code> 控制，关掉时这里所有 bean 都不创建，
 * 调度也不会注册。与系统其它包没有任何依赖。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "watch.enabled", havingValue = "true", matchIfMissing = true)
public class WatchConfig {

    private static final Logger log = LoggerFactory.getLogger(WatchConfig.class);

    private final WatchProperties props;
    private volatile ExecutorService pool;

    public WatchConfig(WatchProperties props) {
        this.props = props;
    }

    @Bean
    public StockNameIndex stockNameIndex() {
        return new StockNameIndex(props.getStockListFile());
    }

    @Bean
    public WatchListLoader watchListLoader(StockNameIndex index) {
        return new WatchListLoader(props.getWatchListFile(), index);
    }

    @Bean
    public SinaQuoteClient sinaQuoteClient() {
        return new SinaQuoteClient(props);
    }

    @Bean
    public DingTalkNotifier dingTalkNotifier() {
        return new DingTalkNotifier(props);
    }

    /** 并发查行情的线程池，线程名统一 watch-quote-* 便于排查 */
    @Bean("watchExecutor")
    public ExecutorService watchExecutor() {
        int n = props.getThreadPoolSize() <= 0 ? 1 : props.getThreadPoolSize();
        ThreadFactory factory = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "watch-quote-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        };
        pool = new ThreadPoolExecutor(n, n, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), factory);
        log.info("[watch] 盯盘已启用：间隔 {}s，阈值 {}%，并发 {}，名单 {}",
                props.getIntervalSeconds(), props.getThresholdPercent(), n, props.getWatchListFile());
        return pool;
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }
}
