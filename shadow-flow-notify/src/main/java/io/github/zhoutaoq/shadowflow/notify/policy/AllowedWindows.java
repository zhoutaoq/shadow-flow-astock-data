package io.github.zhoutaoq.shadowflow.notify.policy;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 通用「允许发送时段」判断：给定时刻是否落在配置的若干时间窗内。
 *
 * <p>这是通知 SDK 的通用能力，不是某个业务的交易时段 —— 它只认识
 * {@code "HH:mm-HH:mm,HH:mm-HH:mm"} 这种字符串与一组星期，至于这些时间段
 * 代表什么（开盘、值班、值守窗口），SDK 不关心、也不该关心。
 *
 * <p>两条刻意的容错，都是为了「配置写坏时不要变成静默故障」：
 * <ul>
 *   <li>时段串为空白 → 视为<b>不限制</b>，一律放行；</li>
 *   <li>时段串全部解析失败 → 同样视为不限制（否则用户会「一条消息都收不到」，
 *       而且不知道该去改哪里）。</li>
 * </ul>
 *
 * <p>时区不在此处硬编码：调用方通过 {@link #in(LocalDateTime, String, Set)} 传入
 * <b>已经换算好的本地时刻</b>，或由 {@code zone} 参数决定怎么取「现在」。
 * 通知系统最容易踩的坑就是「服务器时区不是 +08，时段判断整体偏移」。
 */
public final class AllowedWindows {

    private AllowedWindows() {
    }

    /** 一个时间窗：当天的 {@code [startMinute, endMinute]}，闭区间，单位是「从 00:00 起的分钟」 */
    private static final class Window {
        final int start;
        final int end;

        Window(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }

    /**
     * 当前是否落在允许时段内（按给定时区取「现在」）。
     *
     * @param windows 形如 {@code "09:15-11:30,13:00-15:00"}；空白视为不限制
     * @param days    允许的星期；为空或 null 视为不限星期
     * @param zone    时区；null 表示用系统默认时区
     */
    public static boolean inNow(String windows, Set<DayOfWeek> days, ZoneId zone) {
        LocalDateTime now = zone == null ? LocalDateTime.now() : LocalDateTime.now(zone);
        return in(now, windows, days);
    }

    /**
     * 某时刻是否落在允许时段内。
     *
     * @param now     待判断的本地时刻（调用方需保证已经换算到目标时区）
     * @param windows 时段串；空白或全部写坏时<b>放行</b>
     * @param days    允许的星期；为空或 null 视为不限
     */
    public static boolean in(LocalDateTime now, String windows, Set<DayOfWeek> days) {
        if (now == null) {
            return true;
        }
        if (days != null && !days.isEmpty() && !days.contains(now.getDayOfWeek())) {
            return false;
        }
        List<Window> parsed = parse(windows);
        if (parsed.isEmpty()) {
            return true;
        }
        int minuteOfDay = now.getHour() * 60 + now.getMinute();
        for (Window w : parsed) {
            if (minuteOfDay >= w.start && minuteOfDay <= w.end) {
                return true;
            }
        }
        return false;
    }

    /** 解析时段串；非法片段忽略，全非法则返回空表（调用方按「不限制」处理） */
    public static List<String> parseToText(String windows) {
        List<String> out = new ArrayList<>();
        for (Window w : parse(windows)) {
            out.add(String.format("%02d:%02d-%02d:%02d",
                    w.start / 60, w.start % 60, w.end / 60, w.end % 60));
        }
        return out;
    }

    /**
     * 解析星期串，如 {@code "MO,TU,WE,TH,FR"}；无法识别的一律忽略。
     *
     * <p>两种写法都认：ISO-8601 两字母短码（{@code MO}/{@code TU}/…，配置里更好写）
     * 与枚举全名（{@code MONDAY}/…）。
     *
     * <p><b>注意别用 {@code DayOfWeek.valueOf} 直接解析短码</b>：枚举值叫 {@code MONDAY}，
     * 传 {@code "MO"} 会抛 {@link IllegalArgumentException}。若把这个异常吞掉当「写错了」，
     * 结果就是一个空的星期集合 —— 而下游把空集合解释成「不限星期」，
     * 于是「只在工作日发送」这条规则<b>静默失效</b>，周末照发。
     * 这里显式做映射，并让无法识别的内容只影响它自己那一段。
     */
    public static Set<DayOfWeek> parseDays(String spec) {
        if (spec == null || spec.trim().isEmpty()) {
            return Collections.emptySet();
        }
        Set<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String seg : spec.split(",")) {
            DayOfWeek d = toDay(seg);
            if (d != null) {
                out.add(d);
            }
        }
        return out;
    }

    /** 单个星期片段 → DayOfWeek；识别不了返回 null */
    private static DayOfWeek toDay(String seg) {
        if (seg == null) {
            return null;
        }
        String s = seg.trim().toUpperCase(Locale.ROOT);
        if (s.isEmpty()) {
            return null;
        }
        switch (s) {
            case "MO":
            case "MON":
            case "MONDAY":
                return DayOfWeek.MONDAY;
            case "TU":
            case "TUE":
            case "TUESDAY":
                return DayOfWeek.TUESDAY;
            case "WE":
            case "WED":
            case "WEDNESDAY":
                return DayOfWeek.WEDNESDAY;
            case "TH":
            case "THU":
            case "THURSDAY":
                return DayOfWeek.THURSDAY;
            case "FR":
            case "FRI":
            case "FRIDAY":
                return DayOfWeek.FRIDAY;
            case "SA":
            case "SAT":
            case "SATURDAY":
                return DayOfWeek.SATURDAY;
            case "SU":
            case "SUN":
            case "SUNDAY":
                return DayOfWeek.SUNDAY;
            default:
                return null;
        }
    }

    private static List<Window> parse(String windows) {
        List<Window> out = new ArrayList<>();
        if (windows == null || windows.trim().isEmpty()) {
            return out;
        }
        for (String seg : windows.split(",")) {
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
