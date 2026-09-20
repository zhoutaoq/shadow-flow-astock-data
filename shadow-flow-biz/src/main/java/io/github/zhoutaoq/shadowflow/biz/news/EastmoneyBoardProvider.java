package io.github.zhoutaoq.shadowflow.biz.news;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.zhoutaoq.shadowflow.biz.support.AShareCodes;
import io.github.zhoutaoq.shadowflow.biz.support.SimpleHttp;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 东方财富：热门股走「换手率榜」，板块涨跌幅走「行业板块」。
 *
 * <p>两块都是免费公开接口、不消耗按次计费的供应商额度，走的都是
 * {@code push2…/api/qt/clist/get} 这一套「按字段号取数」的接口。
 *
 * <p><b>热门股为什么是换手率榜</b>：东财原本的人气榜
 * （{@code emappdata.eastmoney.com/stockrank/getAllCurrentList}）实测已不可用 ——
 * 无论 GET/POST、换 UA/Origin/Referer、加减参数、换成同域的 getCurrentList，
 * 上游一律固定回 {@code {"message":"服务异常","status":-1,"code":-2}}，
 * 换 gbapi 那条人气榜通道则直接连不通。所以这里改用东财自己的
 * <b>换手率排行榜</b>（{@code fid=f8}）：换手率高 = 当日资金参与度高，
 * 是「热门」的一个真实口径，而且和另外两家的榜不重复
 * （新浪那边是成交额榜）。<b>页面上如实标注成「换手率榜」，不冒称人气榜。</b>
 *
 * <p>取样范围 {@code fs=m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23} 就是沪深A股全集
 * （深主板 / 创业板 / 沪主板 / 科创板），北交所不在其中，与系统的沪深A股口径一致。
 *
 * <p><b>宿主按序回退</b>：主干 {@code push2.eastmoney.com} 在部分机房出口会被拒
 * （TCP 能连上、TLS 之后空回复），{@code push2delay.eastmoney.com} 实测正常且是实时数据。
 * 两个都留着按顺序试第一个成功的，换机房或东财解封都不用改代码 —— 与
 * {@code MarketIndexService} 里海外指数那套是同一个策略。
 */
@Component
public class EastmoneyBoardProvider implements BoardProvider {

    private static final String ID = "eastmoney";
    private static final String LABEL = "东方财富";

    /** 行情宿主，按顺序试第一个成功的 */
    private static final String[] EM_HOSTS = {
            "https://push2.eastmoney.com",
            "https://push2delay.eastmoney.com",
    };
    private static final String EM_REFERER = "https://quote.eastmoney.com/";

    /** 沪深A股全集：深主板 / 创业板 / 沪主板 / 科创板（不含北交所） */
    private static final String A_SHARE_FS = "m:0+t:6,m:0+t:80,m:1+t:2,m:1+t:23";

    /** 热门股：换手率（f8）降序，带现价 f2 与涨跌幅 f3 */
    private static final String HOT_PATH =
            "/api/qt/clist/get?np=1&fltt=2&invt=2&po=1&fid=f8&fs=" + A_SHARE_FS
                    + "&fields=f2,f3,f8,f12,f14";

    /** 行业板块：m:90 t:2 是行业板块，f:!50 过滤掉退市/异常板块；fltt=2 让东财直接回真实小数。
     *  f128 / f140 / f136 = 领涨股名称 / 代码 / 涨跌幅（v1.5.8 起要代码，名称点开才有地方跳） */
    private static final String SECTOR_PATH =
            "/api/qt/clist/get?np=1&fltt=2&invt=2&po=1&fid=f3&fs=m:90+t:2+f:!50"
                    + "&fields=f2,f3,f12,f14,f128,f136,f140";

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

    // ------------------------------------------------------------ 热门股（换手率榜）

    @Override
    public List<HotStock> hotStocks(int limit) throws Exception {
        return fetch(
                HOT_PATH + "&pn=1&pz=" + Math.max(1, limit),
                body -> parseHot(body, limit),
                "个股");
    }

    private List<HotStock> parseHot(String body, int limit) throws Exception {
        JsonNode diff = mapper.readTree(body).path("data").path("diff");
        List<HotStock> out = new ArrayList<>();
        int rank = 0;
        for (JsonNode node : diff) {
            String code = node.path("f12").asText("");
            String name = node.path("f14").asText("");
            if (code.isEmpty() || name.isEmpty()) {
                continue;
            }
            if (++rank > limit) {
                break;
            }
            HotStock hs = new HotStock(rank, code, name);
            hs.setPrice(emNumber(node.get("f2")));
            hs.setChangePercent(emNumber(node.get("f3")));
            // 换手率(%)：本平台自己排序用的「热门」口径，跨平台不比较
            hs.setHot(emNumber(node.get("f8")));
            out.add(hs);
        }
        return out;
    }

    // ------------------------------------------------------------ 行业板块

    @Override
    public List<SectorRow> sectors(int limit) throws Exception {
        return fetch(
                SECTOR_PATH + "&pn=1&pz=" + Math.max(1, limit),
                body -> parseSectors(body, limit),
                "板块");
    }

    private List<SectorRow> parseSectors(String body, int limit) throws Exception {
        JsonNode diff = mapper.readTree(body).path("data").path("diff");
        List<SectorRow> out = new ArrayList<>();
        int rank = 0;
        for (JsonNode node : diff) {
            String name = node.path("f14").asText("");
            if (name.isEmpty()) {
                continue;
            }
            if (++rank > limit) {
                break;
            }
            SectorRow row = new SectorRow(rank, node.path("f12").asText(""), name,
                    emNumber(node.get("f3")));
            String leader = node.path("f128").asText("");
            if (!leader.isEmpty() && !"-".equals(leader)) {
                row.setLeaderName(leader);
                row.setLeaderChangePercent(emNumber(node.get("f136")));
                // f140 = 领涨股代码（实测 BK1599 → 000504）；缺了或不是 6 位数字就不给代码，
                // 页面那边按「没有代码就不做链接」处理，宁可不点也不跳错票
                String leaderCode = node.path("f140").asText("");
                if (AShareCodes.isAShare(leaderCode)) {
                    row.setLeaderCode(leaderCode);
                }
            }
            out.add(row);
        }
        return out;
    }

    // ------------------------------------------------------------ 公共

    /** 解析器接口：把一段响应体变成若干行 */
    private interface Parser<T> {
        List<T> parse(String body) throws Exception;
    }

    /**
     * 按宿主机顺序试一遍，第一个解析出非空结果的就用；全失败把最后一个异常抛给上层。
     *
     * <p>抛异常而不是返回空 —— 前者是「这次没取到」，后者会被当成「平台没有数据」，
     * 页面上一个显示「取数失败」、一个显示「暂不提供」，语义完全不同。
     */
    private <T> List<T> fetch(String path, Parser<T> parser, String what) throws Exception {
        Exception last = null;
        for (String host : EM_HOSTS) {
            try {
                List<T> rows = parser.parse(SimpleHttp.get(host + path, EM_REFERER));
                if (!rows.isEmpty()) {
                    return rows;
                }
                last = new IllegalStateException("宿主 " + host + " 返回体无有效" + what);
            } catch (Exception e) {
                last = e;
            }
        }
        if (last != null) {
            throw last;
        }
        return new ArrayList<>();
    }

    /** 东财的数值位在无数据时是字符串 "-"，只有真数字才要 */
    private static BigDecimal emNumber(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }
}
