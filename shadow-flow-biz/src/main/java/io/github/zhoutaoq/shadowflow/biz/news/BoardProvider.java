package io.github.zhoutaoq.shadowflow.biz.news;

import java.util.List;

/**
 * 一个行情平台的数据源。
 *
 * <p>四家实测下来能力并不对等（通达信没有可访问的公开接口，同花顺只有个股热榜、没有板块），
 * 所以接口上显式声明两个能力位：页面只渲染真正有数据的平台，不摆空壳、更不拿别家的数据顶替。
 *
 * <p>实现类都是「取不到就抛/返回空」，异常由 {@link BoardService} 统一兜成失败状态，
 * 一个平台挂掉不影响另外两个。
 */
public interface BoardProvider {

    /** 平台标识，用于前端区分卡片：eastmoney / sina / tonghuashun */
    String id();

    /** 平台展示名：东方财富 / 新浪财经 / 同花顺 */
    String label();

    /** 该平台是否提供「热门股排行」 */
    boolean supportsHotStocks();

    /** 该平台是否提供「板块涨跌幅」 */
    boolean supportsSectors();

    /** 热门股排行，按平台自己的名次口径取前 limit 条 */
    List<HotStock> hotStocks(int limit) throws Exception;

    /** 行业板块涨跌幅，按涨跌幅从高到低取前 limit 条 */
    List<SectorRow> sectors(int limit) throws Exception;
}
