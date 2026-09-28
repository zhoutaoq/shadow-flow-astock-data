package io.github.zhoutaoq.shadowflow.biz.web;

import io.github.zhoutaoq.shadowflow.biz.auth.AuthContext;
import io.github.zhoutaoq.shadowflow.biz.config.IntegrationConfigService;
import io.github.zhoutaoq.shadowflow.biz.model.ApiResponse;
import io.github.zhoutaoq.shadowflow.biz.service.StockLinkService;
import io.github.zhoutaoq.shadowflow.biz.watch.PriceWatchTask;
import io.github.zhoutaoq.shadowflow.biz.watch.Quote;
import io.github.zhoutaoq.shadowflow.biz.watch.SinaQuoteClient;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchCards;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchProperties;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchTarget;
import io.github.zhoutaoq.shadowflow.biz.support.PushEnvelope;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyMessage;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 超管专属的「钉钉推送」接口：手动把指定标的的实时行情即时推到系统通知机器人。
 *
 * <p>消息形态与盯盘告警完全一致：整卡按涨跌染色，且<b>整卡可点</b> ——
 * 点消息任意位置跳到「股票名称跳转」配置里选的那个源，不摆候选让人挑。
 *
 * <p>与盯盘告警的关系：<b>只共用卡片渲染（{@link WatchCards}）与机器人出口
 * （{@link NotifyService}），不共用触发逻辑</b> —— 这是人手动触发的独立事件，
 * 消息标记 {@code bypassSchedule}（时段闸门对人放行），但总开关照样拦：
 * 系统通知机器人没配置、或推送总开关关闭时，这里直接报错，不装作推成功。
 *
 * <p>鉴权与 {@link AdminStockLinkController} 同一口径：路径挂在 {@code /api/admin/**} 下
 * 拦截器只保证已登录，超管身份这里再判一次。
 */
@RestController
@RequestMapping("/api/admin/push")
public class AdminPushController {

    private static final Logger log = LoggerFactory.getLogger(AdminPushController.class);

    /** 消息类型：行情快照 / 涨跌停状态 */
    private static final String TYPE_SNAPSHOT = "snapshot";
    private static final String TYPE_LIMIT = "limit";

    private final SinaQuoteClient quoteClient;
    private final NotifyService notify;
    private final IntegrationConfigService integration;
    private final WatchProperties props;
    private final StockLinkService stockLinks;

    public AdminPushController(SinaQuoteClient quoteClient,
                               NotifyService notify,
                               IntegrationConfigService integration,
                               WatchProperties props,
                               StockLinkService stockLinks) {
        this.quoteClient = quoteClient;
        this.notify = notify;
        this.integration = integration;
        this.props = props;
        this.stockLinks = stockLinks;
    }

    /**
     * 实时行情预览：按代码逐只拉新浪行情，附上涨跌停状态，供推送页先看一眼再推。
     * 行情是只读操作，拿不到（停牌 / 代码不存在）就在结果里标注，不整体报错。
     */
    @GetMapping("/quotes")
    public ApiResponse<List<Map<String, Object>>> quotes(@RequestParam("codes") String codes) {
        requireAdmin();
        List<Map<String, Object>> out = new ArrayList<>();
        for (String c : splitCodes(codes)) {
            Quote q = quoteClient.fetch(new WatchTarget(null, c));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c);
            if (q == null) {
                row.put("ok", false);
                row.put("error", "没取到行情（停牌、代码不存在或行情源不可用）");
                out.add(row);
                continue;
            }
            row.put("ok", true);
            row.put("name", q.getName());
            row.put("price", q.priceText());
            row.put("preClose", q.preCloseText());
            row.put("changePercent", q.changePercent() == null ? null : q.changePercent().toPlainString());
            row.put("high", q.highText());
            row.put("low", q.lowText());
            row.put("volume", q.getVolume() == null ? null : q.getVolume().toPlainString());
            row.put("amount", q.getAmount() == null ? null : q.getAmount().toPlainString());
            row.put("timeText", q.getTimeText());
            row.put("limitState", limitState(q));
            out.add(row);
        }
        return ApiResponse.ok(out);
    }

    /**
     * 实时推送：每只标的单独发一条 markdown 卡片（与盯盘告警同一套渲染），
     * 逐条返回发送结果。部分失败不影响其余标的。
     */
    @PostMapping("/send")
    public ApiResponse<List<Map<String, Object>>> send(@RequestBody Map<String, Object> body) {
        requireAdmin();
        String webhook = integration.dingtalkWebhook();
        if (webhook == null || webhook.trim().isEmpty()) {
            throw new IllegalArgumentException("系统通知机器人还没配置，先到「系统配置」页填好再推");
        }
        Object typeObj = body.get("type");
        String type = typeObj == null ? TYPE_SNAPSHOT : String.valueOf(typeObj);
        if (!TYPE_SNAPSHOT.equals(type) && !TYPE_LIMIT.equals(type)) {
            throw new IllegalArgumentException("不认识的消息类型：" + type);
        }
        Object codesObj = body.get("codes");
        if (!(codesObj instanceof List) || ((List<?>) codesObj).isEmpty()) {
            throw new IllegalArgumentException("请先选择要推送的标的");
        }

        final String prefix = PushEnvelope.of(props.getDingtalk().getKeyword());
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : (List<?>) codesObj) {
            String c = o == null ? "" : String.valueOf(o).trim();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", c);
            if (!c.matches("\\d{5,6}")) {
                row.put("ok", false);
                row.put("error", "代码格式不对");
                out.add(row);
                continue;
            }
            Quote q = quoteClient.fetch(new WatchTarget(null, c));
            if (q == null) {
                row.put("ok", false);
                row.put("error", "没取到行情，未推送");
                out.add(row);
                continue;
            }
            row.put("name", q.getName());
            try {
                String card = TYPE_LIMIT.equals(type) ? limitCard(q) : snapshotCard(q);
                // 整卡可点：点消息任意位置跳到系统配置的跳转源（没配就退回普通卡片）
                NotifyMessage.Builder mb = NotifyMessage.to(webhook)
                        .prefix(prefix)
                        .content(card)
                        .cardTitle(WatchCards.titleOf(card))
                        .markdown()
                        // 人手动点的推送是独立事件，放行时段闸门；总开关不受此影响
                        .bypassSchedule();
                String url = linkOf(q);
                if (url != null && !url.trim().isEmpty()) {
                    mb.link(url.trim());
                }
                boolean ok = notify.send(mb.build());
                row.put("ok", ok);
                if (!ok) {
                    row.put("error", "发送失败（机器人不可用或被拦截），详情看服务端日志");
                }
            } catch (RuntimeException e) {
                log.warn("[push] 手动推送 {}({}) 失败：{}", q.getName(), c, e.getMessage());
                row.put("ok", false);
                row.put("error", "推送失败：" + e.getMessage());
            }
            out.add(row);
        }
        return ApiResponse.ok(out);
    }

    /* ============ 内部工具 ============ */

    private void requireAdmin() {
        if (!AuthContext.isAdmin()) {
            throw new IllegalArgumentException("只有超管可以手动推送");
        }
    }

    private static List<String> splitCodes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        for (String s : raw.split("[,，;；\\s]+")) {
            if (!s.trim().isEmpty()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    /** 快照卡片（整卡按涨跌染色），渲染与告警共用 {@link WatchCards} */
    private String snapshotCard(Quote q) {
        return WatchCards.snapshot(q.getName(), toneOf(q),
                q.priceText(),
                q.changePercentText(),
                q.preCloseText(),
                q.highText(),
                q.lowText(),
                PriceWatchTask.formatVolume(q.getVolume()),
                PriceWatchTask.formatAmount(q.getAmount()),
                q.getTimeText());
    }

    /** 整卡色调：涨红 / 跌绿 / 平盘与无涨跌幅时用中性灰（不标色） */
    private static String toneOf(Quote q) {
        BigDecimal pct = q.changePercent();
        if (pct == null || pct.signum() == 0) {
            return WatchCards.FLAT;
        }
        return pct.signum() > 0 ? WatchCards.UP : WatchCards.DOWN;
    }

    /**
     * 涨跌停状态卡：只在该标的<b>当前正封在板上</b>时可用 —— 手动推送没有「刚封上 / 刚打开」
     * 的时点语义，硬套告警措辞会说出假话，所以不在板上直接报错。
     */
    private String limitCard(Quote q) {
        int state = limitState(q);
        if (state == 0) {
            throw new IllegalArgumentException(q.getName() + " 当前不在涨跌停上，推不了这条");
        }
        return WatchCards.limit(q.getName(), state, true,
                q.priceText(),
                q.changePercentText(),
                null,
                null,
                PriceWatchTask.formatAmount(q.getAmount()));
    }

    /** 涨跌停状态：1 = 涨停，-1 = 跌停，0 = 不在板上（按昨收 × 板块涨跌幅限制推板价，精确到分） */
    private static int limitState(Quote q) {
        if (q.getPrice() == null || q.getPreClose() == null || q.getPreClose().signum() <= 0) {
            return 0;
        }
        BigDecimal up = limitPrice(q.getPreClose(), q.getCode(), q.getName(), true);
        BigDecimal down = limitPrice(q.getPreClose(), q.getCode(), q.getName(), false);
        if (q.getPrice().compareTo(up) >= 0) {
            return 1;
        }
        if (q.getPrice().compareTo(down) <= 0) {
            return -1;
        }
        return 0;
    }

    /** 涨跌停价：北交所 ±30%，科创板 / 创业板 ±20%，ST ±5%，其余 ±10%（四舍五入到分） */
    private static BigDecimal limitPrice(BigDecimal preClose, String code, String name, boolean up) {
        String c = code == null ? "" : code.trim();
        char head = c.isEmpty() ? '0' : c.charAt(0);
        BigDecimal ratio;
        if (head == '4' || head == '8') {
            ratio = new BigDecimal("0.30");
        } else if (c.startsWith("688") || c.startsWith("300") || c.startsWith("301")) {
            ratio = new BigDecimal("0.20");
        } else if (name != null && name.contains("ST")) {
            ratio = new BigDecimal("0.05");
        } else {
            ratio = new BigDecimal("0.10");
        }
        BigDecimal factor = up ? BigDecimal.ONE.add(ratio) : BigDecimal.ONE.subtract(ratio);
        return preClose.multiply(factor).setScale(2, RoundingMode.HALF_UP);
    }

    /** 整卡跳转地址：与页面点标的跳同一个源；拼不出来就返回 null，卡片照推不挂跳转 */
    private String linkOf(Quote q) {
        try {
            return stockLinks.primaryLink(q.getCode(), q.getName());
        } catch (RuntimeException e) {
            log.warn("[push] 行情跳转地址拼装失败，本条消息不跳转：{}", e.getMessage());
            return null;
        }
    }
}
