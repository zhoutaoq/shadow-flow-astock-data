package io.github.zhoutaoq.shadowflow.biz.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 可用数量日切（需求 v1.5.6 第 ⑥ 条）。
 *
 * <p>每天 00:00（北京时间）把<b>所有用户、所有账户、所有标的</b>的可用数量对齐到持仓数量 ——
 * 语义就是隔夜结算：头一天买进来的份额到了新交易日全部变成可卖。
 *
 * <p>真正的遍历与落盘在 {@link AccountService#resetAvailableQty()}，这里只负责「什么时候做」：
 * 业务逻辑放在 Service 里才能被单独验证，也免得调度细节渗进账户模型。
 *
 * <p>时区固定 {@code Asia/Shanghai}：服务器时区不是东八区时，按默认时区算会落到别的时刻，
 * 口径与 {@code TradeSessions} 保持一致。
 */
@Component
@ConditionalOnProperty(name = "waizao.account.available-reset-enabled", havingValue = "true", matchIfMissing = true)
public class AvailableQtyResetTask {

    private static final Logger log = LoggerFactory.getLogger(AvailableQtyResetTask.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AccountService accountService;

    public AvailableQtyResetTask(AccountService accountService) {
        this.accountService = accountService;
    }

    /**
     * 每天 00:00 触发；表达式可用 {@code waizao.account.available-reset-cron} 覆盖
     * （本地验证时改成每分钟一次，不用真等到半夜）。
     */
    @Scheduled(cron = "${waizao.account.available-reset-cron:0 0 0 * * ?}", zone = "Asia/Shanghai")
    public void resetDaily() {
        long start = System.currentTimeMillis();
        try {
            int changed = accountService.resetAvailableQty();
            log.info("[account] 可用数量日切（{}）：对齐 {} 条持仓，耗时 {} ms",
                    LocalDateTime.now().format(TS), changed, System.currentTimeMillis() - start);
        } catch (Exception e) {
            // 定时任务里把异常抛出去会让这个调度线程直接停掉、以后再也不触发，所以一律兜住
            log.error("[account] 可用数量日切失败：{}", e.getMessage(), e);
        }
    }
}
