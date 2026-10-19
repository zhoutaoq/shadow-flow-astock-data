package io.github.zhoutaoq.shadowflow.biz.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 账户分钟走势采样（需求 v1.6.7 第 ③ 条）。
 *
 * <p>每分钟（交易时段的 9–15 点内）给每本账打一个点，供详情页画 1 分钟曲线。
 * 具体怎么算、什么时候算、什么时候清理都在 {@link AccountTrendService} 里 ——
 * 与 {@code AvailableQtyResetTask} 一样，这里只负责「什么时候做」，业务逻辑单独可验证。
 *
 * <p>表达式每 10 秒一次而不是「每分钟整点一次」：整点触发时如果上一次采样还没跑完
 * （行情接口慢），这一分钟就整条缺了；10 秒一次由 {@link AccountTrendService#sampleAll()}
 * 内部的交易时段判断 + 分钟级 upsert 自然去重，最多写两次同一个分钟，不会写出重复点。
 * 想调整节奏改 {@code shadowflow.account.trend-cron} 即可。
 */
@Component
@ConditionalOnProperty(name = "shadowflow.account.trend-enabled", havingValue = "true", matchIfMissing = true)
public class AccountTrendTask {

    private static final Logger log = LoggerFactory.getLogger(AccountTrendTask.class);

    private final AccountTrendService trendService;

    public AccountTrendTask(AccountTrendService trendService) {
        this.trendService = trendService;
    }

    /** 交易时段每 10 秒跑一次（9–15 点整点范围内），时段外的调用直接返回 0 */
    @Scheduled(cron = "${shadowflow.account.trend-cron:0/10 * 9-15 * * MON-FRI}", zone = "Asia/Shanghai")
    public void sample() {
        try {
            trendService.sampleAll();
        } catch (Exception e) {
            // 定时任务里把异常抛出去会让这个调度线程直接停掉、以后再也不触发，所以一律兜住
            log.error("[account] 走势采样失败：{}", e.getMessage(), e);
        }
    }
}
