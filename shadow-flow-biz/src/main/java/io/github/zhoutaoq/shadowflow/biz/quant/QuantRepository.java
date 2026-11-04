package io.github.zhoutaoq.shadowflow.biz.quant;

import java.util.List;

/**
 * 量化模块的统一仓储（沿用项目「一份模型 + 两种落点」的约定）。
 *
 * <ul>
 *   <li><b>数据库模式</b>：五张 {@code shadowflow_quant_*} 表；</li>
 *   <li><b>无数据库模式</b>：{@code ./data/quant/quant-{userId}.json}，一个用户的策略连同
 *       持仓、成交、每日快照、日内分时放在同一个文件里 —— 策略是根，其余都挂在它下面，
 *       拆文件只会带来跨文件一致性问题。</li>
 * </ul>
 *
 * <p><b>所有读写都带 userId</b>：策略是用户级数据。策略下的子表（持仓/成交/日快照）
 * 因为已经有 strategyId 这个强约束，接口上只传 strategyId，由实现内部
 * 保证「策略属于这个人」这一层在业务侧先校验过（{@code QuantService} 统一在入口校验）。
 */
public interface QuantRepository {

    /* ==================== 策略 ==================== */

    /** 所有建过策略的用户 id：初始化任务要跨用户刷新标的 */
    List<Long> listStrategyUserIds();

    /** 某个用户的全部策略，按 id 升序 */
    List<QuantStrategy> listStrategies(long userId);

    /** 全部启用中的策略：决策轮询用。跨用户，返回里带 userId */
    List<QuantStrategy> listEnabledStrategies();

    /** 取一个策略；不属于该用户返回 null */
    QuantStrategy findStrategy(long userId, long id);

    /** 新建：id 由仓储分配 */
    QuantStrategy createStrategy(QuantStrategy strategy);

    /** 保存策略全部字段（含待生效变更） */
    void saveStrategy(QuantStrategy strategy);

    /** 删除策略，连同其持仓、成交、日快照、日内分时 */
    void deleteStrategy(long userId, long id);

    /* ==================== 持仓 ==================== */

    /** 某个策略的持仓，按代码升序 */
    List<QuantHolding> listHoldings(long strategyId);

    /** 按 (strategyId, code) 定位：有则更新、无则新增 */
    QuantHolding saveHolding(QuantHolding holding);

    /** 删一条持仓（数量归零时调用） */
    void deleteHolding(long strategyId, long id);

    /* ==================== 成交 ==================== */

    /** 最近 N 笔成交，新的在前 */
    List<QuantTrade> listTrades(long strategyId, int limit);

    /** 追加一笔成交 */
    QuantTrade saveTrade(QuantTrade trade);

    /* ==================== 每日快照 ==================== */

    /** 取某天的快照；没有返回 null */
    QuantDaily findDaily(long strategyId, String tradeDate);

    /** 按 (strategyId, tradeDate) 覆盖写入 */
    QuantDaily saveDaily(QuantDaily daily);

    /** 区间内每日快照，按日期升序；from/to 为空表示不限 */
    List<QuantDaily> listDailies(long strategyId, String from, String to);

    /* ==================== 日内分时 ==================== */

    /** 覆盖写一个分时点（同一策略、同一天、同一分钟只留一条） */
    void saveIntraday(QuantIntradayPoint point);

    /** 某个策略某天的全部分时点，按时刻升序 */
    List<QuantIntradayPoint> listIntraday(long strategyId, String tradeDate);

    /** 清掉某天之前的分时数据：日内点一天 240 行，不清理会让文件无限膨胀 */
    void purgeIntradayBefore(long strategyId, String tradeDate);
}
