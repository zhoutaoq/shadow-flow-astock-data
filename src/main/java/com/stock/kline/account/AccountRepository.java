package com.stock.kline.account;

import java.util.List;

/**
 * 账户与持仓的统一仓储（需求 v1.5.1 第 ③ 条 / v1.5.2 第 ④ 条）。
 *
 * <p>沿用项目既有的「一份模型 + 两种落点」约定：
 * <ul>
 *   <li>数据库模式（{@code waizao.db.enabled=true}）：账户落 {@code waizao_account}，
 *       持仓落 {@code waizao_position}（带 {@code account_id}）；</li>
 *   <li>无数据库模式：落 {@code ./data/account/account-{userId}.json}，
 *       一个用户的<b>所有账户与持仓</b>放在同一个文件里 —— 它们天然是一棵树，
 *       拆文件反而要处理跨文件一致性。</li>
 * </ul>
 *
 * <p><b>所有读写都必须带 {@code userId}</b>：账户是用户级数据，别人的账户与持仓一行都碰不到。
 * 账户级操作再带 {@code accountId} 定位到具体一本账。
 */
public interface AccountRepository {

    /* ==================== 账户 ==================== */

    /** 某个用户的全部账户，按账户 id 升序；没建过返回空列表 */
    List<Account> listAccounts(long userId);

    /** 取某个账户；不属于该用户的返回 null（由业务侧决定是否给一份空账户） */
    Account findAccount(long userId, long accountId);

    /** 新建账户：id 由仓储分配，返回落库后的账户 */
    Account createAccount(Account account);

    /** 保存账户设置（名称 / 期初市值 / 可用金额 / 备注），按 (userId, id) 定位，不存在则新建 */
    void saveAccount(Account account);

    /** 删除账户，连同其下全部持仓；不是自己的删不动 */
    void deleteAccount(long userId, long accountId);

    /* ==================== 持仓 ==================== */

    /** 某个账户的持仓，按代码升序 */
    List<Position> listPositions(long userId, long accountId);

    /** 新增或更新一条持仓（按主键定位，id 为空则新增）；返回落库后的持仓 */
    Position savePosition(Position position);

    /** 删除一条持仓；不是自己的账户里的删不动 */
    void deletePosition(long userId, long accountId, long id);

    /** 删除某个账户的全部持仓（清空持仓用） */
    void deleteAllPositions(long userId, long accountId);
}
