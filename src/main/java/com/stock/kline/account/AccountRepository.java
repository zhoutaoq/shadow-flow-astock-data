package com.stock.kline.account;

import java.util.List;

/**
 * 账户与持仓的统一仓储（需求 v1.5.1 第 ③ 条）。
 *
 * <p>沿用项目既有的「一份模型 + 两种落点」约定：
 * <ul>
 *   <li>数据库模式（{@code waizao.db.enabled=true}）：落 {@code waizao_account} /
 *       {@code waizao_position} 两张表；</li>
 *   <li>无数据库模式：落 {@code ./data/account/account-{userId}.json}，
 *       账户与持仓放在同一个文件里 —— 两者天然一对一，拆两个文件反而要处理一致性。</li>
 * </ul>
 *
 * <p>所有读写都必须带 {@code userId}：账户是用户级数据，别人的持仓一行都碰不到。
 */
public interface AccountRepository {

    /** 取某个用户的账户；没建过返回 null（由业务侧决定是否给一份空账户） */
    Account findAccount(long userId);

    /** 保存账户设置（期初市值 / 可用金额 / 备注），不存在则新建 */
    void saveAccount(Account account);

    /** 某个用户的全部持仓，按代码升序 */
    List<Position> listPositions(long userId);

    /** 新增或更新一条持仓（按主键定位，id 为空则新增）；返回落库后的持仓 */
    Position savePosition(Position position);

    /** 删除一条持仓；不是自己的删不动 */
    void deletePosition(long userId, long id);

    /** 删除某个用户的全部持仓（清空持仓用） */
    void deleteAllPositions(long userId);
}
