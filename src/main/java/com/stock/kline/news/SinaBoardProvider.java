package com.stock.kline.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.kline.support.AShareCodes;
import com.stock.kline.support.SimpleHttp;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 新浪财经：热门股走「A股成交额榜」，板块涨跌幅走「新浪行业板块」。
 *
 * <p>新浪没有现成的「人气榜」，这里用<b>成交额从高到低</b>作为「热门」的口径 —— 成交额是
 * 真实资金参与度，比网站的点击量排榜更接近「热门股」的本意，而且这个接口本来就支持按
 * amount 排序、不用自己算。
 *
 * <p>两个接口都在 <code>vip.stock.finance.sina.com.cn</code> 下，都要带
 * <code>finance.sina.com.cn</code> 的 Referer，否则返回空体。
 *
 * <p>行业板块那个是 <code>var S_Finance_bankuai_sinaindustry = {...}</code> 的 JS 赋值语句，
 * 不是纯 JSON，取值时要把前面的变量声明剥掉再交给 Jackson。每个板块的值本身是一条
 * 逗号分隔的定长字段串（下标含义见下面的常量），涨跌幅在第 6 个字段上。
 */
@Component
public class SinaBoardProvider implements BoardProvider {

    private static final String ID = "sina";
    private static final String LABEL = "新浪财经";
    private static final String REFERER = "https://finance.sina.com.cn/";

    /** A股成交额榜：node=hs_a 全部A股，sort=amount 按成交额，asc=0 降序 */
    private static final String RANK_URL =
            "https://vip.stock.finance.sina.com.cn/quotes_service/api/json_v2.php/"
                    + "Market_Center.getHQNodeData?page=1&sort=amount&asc=0&node=hs_a"
                    + "&symbol=&_s_r_a=page&num=";

    /**
     * 一次多取几行：这个节点会把北交所一起返回，过滤掉之后还要够 limit 条。
     *
     * <p>实测踩过：集合竞价阶段成交额全为 0、排序退化成随机，前 20 条恰好全是
     * {@code bj92xxxx}，一条 A 股都不剩，页面上就是一张空表。按 5 倍取样后
     * 仍有足够的 A 股，且因为 A 股成交额远大于北交所、多取不影响榜单名次。
     */
    private static final int FETCH_FACTOR = 5;
    private static final int FETCH_MAX = 100;

    /** 行业板块：一条 JS 赋值语句，值是「代码,名称,家数,均价,涨跌额,涨跌幅,…」 */
    private static final String SECTOR_URL =
            "https://vip.stock.finance.sina.com.cn/q/view/newSinaHy.php";

    /** 板块值串的下标：0 代码 / 1 名称 / 2 家数 / 5 涨跌幅 / 9 领涨股涨跌幅 / 12 领涨股名称 */
    private static final int S_CODE = 0;
    private static final int S_NAME = 1;
    private static final int S_PCT = 5;
    private static final int S_LEADER_PCT = 9;
    private static final int S_LEADER_NAME = 12;
    /** 板块值串至少要这么多段才敢按下标取 */
    private static final int S_MIN_FIELDS = 13;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return LABEL;
    }

    @Override
    public boolean supportsHotStocks() {
        return true;
    }

    @Override
    public boolean supportsSectors() {
        return true;
    }

    // ------------------------------------------------------------ 热门股（成交额榜）

    @Override
    public List<HotStock> hotStocks(int limit) throws Exception {
        int want = Math.max(1, limit);
        int fetch = Math.min(FETCH_MAX, Math.max(want, want * FETCH_FACTOR));
        String body = SimpleHttp.get(RANK_URL + fetch, REFERER);
        JsonNode arr = mapper.readTree(body);
        if (!arr.isArray()) {
            return new ArrayList<>();
        }
        List<HotStock> out = new ArrayList<>();
        for (JsonNode node : arr) {
            String code = node.path("code").asText("");
            // 该节点会把北交所一起返回（实测有 bj920000），系统只做沪深A股，直接丢掉
            if (!AShareCodes.isAShare(code)) {
                continue;
            }
            // 过滤后名次会断档，重排成 1..N，页面上的排名才是连续的
            HotStock hs = new HotStock(out.size() + 1, code, node.path("name").asText(code));
            hs.setPrice(num(node.path("trade").asText(null)));
            hs.setChangePercent(num(node.path("changepercent").asText(null)));
            hs.setAmount(num(node.path("amount").asText(null)));
            out.add(hs);
            if (out.size() >= want) {
                break;
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 行业板块

    @Override
    public List<SectorRow> sectors(int limit) throws Exception {
        String body = SimpleHttp.get(SECTOR_URL, REFERER);
        JsonNode obj = mapper.readTree(stripAssignment(body));
        List<SectorRow> all = new ArrayList<>();
        java.util.Iterator<JsonNode> it = obj.elements();
        while (it.hasNext()) {
            String[] f = it.next().asText("").split(",");
            if (f.length < S_MIN_FIELDS) {
                continue;
            }
            String name = f[S_NAME].trim();
            if (name.isEmpty()) {
                continue;
            }
            SectorRow row = new SectorRow(0, f[S_CODE].trim(), name, num(f[S_PCT]));
            String leader = f[S_LEADER_NAME].trim();
            if (!leader.isEmpty() && !"--".equals(leader)) {
                row.setLeaderName(leader);
                row.setLeaderChangePercent(num(f[S_LEADER_PCT]));
            }
            all.add(row);
        }
        // 接口回的顺序不保证按涨跌幅排，这里统一按涨跌幅降序（取不到值的沉底）再排名次
        all.sort(Comparator.comparing(SectorRow::getChangePercent,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<SectorRow> out = new ArrayList<>();
        for (SectorRow row : all) {
            if (out.size() >= limit) {
                break;
            }
            row.setRank(out.size() + 1);
            out.add(row);
        }
        return out;
    }

    /**
     * 把 <code>var X = {...};</code> 剥成纯 JSON。
     *
     * <p>只截第一个 <code>{</code> 到最后一个 <code>}</code>：开头是变量声明、结尾可能带分号，
     * 中间那段本身就是合法 JSON，比写正则去匹配变量名稳。
     */
    private static String stripAssignment(String body) {
        int begin = body.indexOf('{');
        int end = body.lastIndexOf('}');
        return (begin < 0 || end <= begin) ? "{}" : body.substring(begin, end + 1);
    }

    /** 新浪的数字一律是字符串（"0.000"），空串 / 非法一律给 null 表示「平台没给」 */
    private static BigDecimal num(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
