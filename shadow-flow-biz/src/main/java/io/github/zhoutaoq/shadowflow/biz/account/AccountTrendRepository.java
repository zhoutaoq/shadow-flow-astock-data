package io.github.zhoutaoq.shadowflow.biz.account;

import java.util.List;

/**
 * 账户分钟走势（需求 v1.6.7 第 ③ 条）的统一仓储，两种落点：
 * <ul>
 *   <li><b>无数据库模式</b>：{@code ./data/account/trend-{userId}.json}，
 *       一个用户的所有账户、所有日期放在同一个文件里 —— 与账户文件同样的理由，
 *       它们天然是一棵树，拆文件只会多出跨文件一致性问题；</li>
 *   <li><b>数据库模式</b>：{@code shadowflow_account_trend}，
 *       唯一键 (account_id, trade_date, minute) 保证同一分钟只留一条。</li>
 * </ul>
 *
 * <p>读写都带 {@code userId}：走势同样是用户级数据，别人的账户曲线一行都不该被读到。
 */
public interface AccountTrendRepository {

    /**
     * 写入一个分钟点（同一账户 / 日期 / 分钟已存在则覆盖）。
     *
     * <p>用「覆盖」而不是「跳过」：页面在盘中会顺手补一个当前分钟的点，
     * 它比定时任务采样那一刻的数字更新，应该以最后写入的为准。
     */
    void savePoint(long userId, long accountId, String date, AccountTrendPoint point);

    /** 某个账户某一天的分钟点，按分钟升序 */
    List<AccountTrendPoint> list(long userId, long accountId, String date);

    /**
     * 删掉早于 {@code date} 的点（保留 {@code date} 当天及之后）。
     *
     * <p>走势是「看看今天/前几天长什么样」的数据，不是审计留痕；
     * 一天 240 个点，留太久会让文件无限膨胀。
     *
     * @return 实际删除的条数
     */
    int purgeBefore(String date);

    /** 账户被删除时一并清掉它的走势 */
    void deleteAccount(long userId, long accountId);
}
