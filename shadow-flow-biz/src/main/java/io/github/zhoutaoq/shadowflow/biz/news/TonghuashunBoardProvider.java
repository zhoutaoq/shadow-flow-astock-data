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
 * 同花顺：热门股走「个股热度榜」（热榜），<b>不提供板块数据</b>。
 *
 * <p>热榜用的是同花顺公开的热榜接口（{@code dq.10jqka.com.cn/fuyao/hot_list_data/…/v1/stock}），
 * 它给的信息比另外两家多：除了名次/名称/涨跌幅，还有热度值和一个题材标签
 * （如「首板涨停」），页面上当小徽标显示。
 *
 * <p><b>为什么没有板块</b>：实测同花顺的板块排行只有
 * <ul>
 *   <li>{@code dq.10jqka.com.cn/…/v1/plate} —— 路径存在，但无论怎么组合参数都固定返回
 *       {@code {"status_code":-1,"status_msg":"Param Error."}}；</li>
 *   <li>{@code q.10jqka.com.cn/thshy/…/ajax/1/} —— 直接 401，需要 hexin-v 反爬令牌，
 *       那是一次 JS 挑战，服务端稳定复现不了。</li>
 * </ul>
 * 行业页的 HTML 里只有侧边栏的板块代码列表，排行榜数据是 AJAX 拉的，拿不到。
 * 所以这里如实声明「不支持板块」，由 {@link BoardService} 明确告诉前端，
 * <b>不拿别的平台的数据顶替同花顺</b>。
 */
@Component
public class TonghuashunBoardProvider implements BoardProvider {

    private static final String ID = "tonghuashun";
    private static final String LABEL = "同花顺";
    private static final String REFERER = "https://eq.10jqka.com.cn/";

    /** 个股热榜：stock_type=a 沪深A股，type=hour 小时榜，list_type=normal 普通榜 */
    private static final String HOT_URL =
            "https://dq.10jqka.com.cn/fuyao/hot_list_data/out/hot_list/v1/stock"
                    + "?stock_type=a&type=hour&list_type=normal";

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
        return false;
    }

    @Override
    public List<HotStock> hotStocks(int limit) throws Exception {
        String body = SimpleHttp.get(HOT_URL, REFERER);
        JsonNode root = mapper.readTree(body);
        // 接口不成功时别当成「没有热门股」，抛出去让上层标成失败，页面才能区分「空」和「挂了」
        if (root.path("status_code").asInt(-1) != 0) {
            throw new IllegalStateException("同花顺热榜返回 " + root.path("status_msg").asText("异常"));
        }
        JsonNode list = root.path("data").path("stock_list");
        if (!list.isArray()) {
            return new ArrayList<>();
        }
        List<HotStock> out = new ArrayList<>();
        for (JsonNode node : list) {
            String code = node.path("code").asText("");
            if (!AShareCodes.isAShare(code)) {
                continue;
            }
            // order 是平台给的名次；缺了就按返回顺序补，保证页面上一定有排名
            int order = node.path("order").asInt(out.size() + 1);
            HotStock hs = new HotStock(order, code, node.path("name").asText(code));
            hs.setChangePercent(emNumber(node.get("rise_and_fall")));
            hs.setHot(emNumber(node.get("rate")));
            String tag = node.path("tag").path("popularity_tag").asText("");
            if (!tag.isEmpty() && !"null".equals(tag)) {
                hs.setTag(tag);
            }
            out.add(hs);
            if (out.size() >= limit) {
                break;
            }
        }
        return out;
    }

    @Override
    public List<SectorRow> sectors(int limit) {
        // 该平台不提供板块数据；由 BoardService 依据 supportsSectors() 直接短路，正常不会走到这
        return new ArrayList<>();
    }

    /**
     * 同花顺的数值位有的给数字、有的给字符串 —— 实测 {@code rise_and_fall} 是数字，
     * 而 {@code rate} 是 <code>"594582.0"</code> 这样的字符串。只认数字节点会让
     * 热度列整列变空，所以两种都要接。
     */
    private static BigDecimal emNumber(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        String s = node.asText("").trim();
        if (s.isEmpty() || "null".equals(s) || "-".equals(s)) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
