package io.github.zhoutaoq.shadowflow.biz.watch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A 股交易时段判断，只做一件事：<b>给定时刻是否落在允许的时段内</b>。
 *
 * <p>为什么要单独拎一个类出来：这个判断有三处消费方（推送闸门、行情抓取闸门、关键时点播报），
 * 早先各自写一份「周一至周五 + hhmm 比大小」，口径一旦不同就会互相打架。
 * 这里只有一份实现，并且<b>不依赖 Spring</b>，可以直接写个 main 跑边界用例。
 *
 * <p><b>时区固定为北京时间</b>（{@link #CN}）：A 股的 09:30 说的是北京时间，
 * 服务器时区不是 +08 时用 {@code LocalDateTime.now()} 会整体偏移，必须显式取时区。
 *
 * <p><b>不含法定节假日</b>：节假日按周一至周五照常放行 —— 休市日行情是上一交易日的快照，
 * 价格不动，规则一般不会触发；真要判断得外挂交易日历，不在本模块范围内。
 */
public final class TradeSessions {

    private static final Logger log = LoggerFactory.getLogger(TradeSessions.class);

    /** 北京时间：所有时段判断都用它 */
    public static final ZoneId CN = ZoneId.of("Asia/Shanghai");

    /**
     * 默认「可推送」时段：含 09:15 起的集合竞价。
     *
     * <p>下界取 09:15 而不是 09:30，是因为 {@link WatchKeyTimePusher} 的第一条例行播报
     * 就排在 09:15:15；若从 09:30 起算，集合竞价那三条会被静默掉。
     */
    public static final String DEFAULT_SESSIONS = "09:15-11:30,13:00-15:00";

    /** 「该不该向行情接口发请求」的时段：只有真的在撮合的价格才有意义 */
    public static final String FETCH_SESSIONS = "09:30-11:30,13:00-15:00";

    /** 配置写坏时只告警一次，不要每轮刷日志 */
    private static final AtomicBoolean WARNED = new AtomicBoolean(false);

    private TradeSessions() {
    }

    public static LocalDateTime nowCn() {
        return LocalDateTime.now(CN);
    }

    /** 一个时段：当天的 {@code [startMinute, endMinute]}，闭区间，单位是「从 00:00 起的分钟」 */
    private static final class Window {
        final int start;
        final int end;

        Window(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 当前（北京时间）是否在给定时段内。
     *
     * @param sessions 形如 {@code "09:15-11:30,13:00-15:00"} 的时段串；空白视为不限制
     */
    public static boolean inNow(String sessions) {
        return in(nowCn(), sessions);
    }

    /**
     * 某时刻是否在给定时段内（周一至周五）。
     *
     * @param now      待判断时刻，必须是北京时间
     * @param sessions 时段串；为空白或全部写坏时<b>放行</b>——
     *                 配置错误不该表现成「一条消息都收不到」这种静默故障
     */
    public static boolean in(LocalDateTime now, String sessions) {
        if (now == null) {
            return true;
        }
        List<Window> windows = parse(sessions);
        if (windows.isEmpty()) {
            return true;
        }
        if (now.getDayOfWeek().getValue() > 5) {
            return false;
        }
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        for (Window w : windows) {
            if (minuteOfDay >= w.start && minuteOfDay <= w.end) {
                return true;
            }
        }
        return false;
    }

    /** 解析时段串；非法片段忽略，全非法则返回空表（调用方按「不限制」处理） */
    static List<Window> parse(String sessions) {
        List<Window> out = new ArrayList<>();
        if (sessions == null || sessions.trim().isEmpty()) {
            return out;
        }
        for (String seg : sessions.split(",")) {
            String s = seg.trim();
            if (s.isEmpty()) {
                continue;
            }
            int dash = s.indexOf('-');
            if (dash <= 0 || dash >= s.length() - 1) {
                continue;
            }
            int start = toMinute(s.substring(0, dash));
            int end = toMinute(s.substring(dash + 1));
            if (start < 0 || end < 0 || end < start) {
                continue;
            }
            out.add(new Window(start, end));
        }
        if (out.isEmpty() && !sessions.trim().isEmpty() && WARNED.compareAndSet(false, true)) {
            log.warn("[watch] 交易时段配置无法解析（{}），已按「不限制时段」处理，请检查 watch.dingtalk.trade-sessions", sessions);
        }
        return out;
    }

    /** {@code "09:30"} → 570；非法返回 -1 */
    private static int toMinute(String hhmm) {
        String s = hhmm.trim();
        int colon = s.indexOf(':');
        if (colon <= 0 || colon >= s.length() - 1) {
            return -1;
        }
        try {
            int h = Integer.parseInt(s.substring(0, colon).trim());
            String mmText = s.substring(colon + 1).trim();
            int m = mmText.length() > 2 ? -1 : Integer.parseInt(mmText);
            if (h < 0 || h > 24 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
