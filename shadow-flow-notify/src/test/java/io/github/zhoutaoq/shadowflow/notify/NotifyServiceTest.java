package io.github.zhoutaoq.shadowflow.notify;

import io.github.zhoutaoq.shadowflow.notify.api.Notifier;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyMessage;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyResult;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyService;
import io.github.zhoutaoq.shadowflow.notify.api.NotifyTargetResolver;
import io.github.zhoutaoq.shadowflow.notify.config.NotifyProperties;
import io.github.zhoutaoq.shadowflow.notify.policy.AllowedWindows;
import io.github.zhoutaoq.shadowflow.notify.policy.AllowedWindowsGate;
import io.github.zhoutaoq.shadowflow.notify.support.NotifyText;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 自测：不连网络、不依赖 Spring，纯逻辑验证。
 *
 * <p>用「假通道」记录被投递的内容，从而断言门面串起来的整条链路
 * （总开关 → 闸门 → 目标解析 → 通道路由 → 前缀渲染）行为正确。
 */
class NotifyServiceTest {

    /** 记录型假通道 */
    private static final class FakeNotifier implements Notifier {
        final List<String> sent = new ArrayList<>();
        final AtomicReference<String> lastEndpoint = new AtomicReference<>();

        @Override
        public String channel() {
            return "fake";
        }

        @Override
        public NotifyResult send(String endpoint, NotifyMessage message) {
            lastEndpoint.set(endpoint);
            sent.add(message.render());
            return NotifyResult.ok();
        }
    }

    private static NotifyProperties props() {
        NotifyProperties p = new NotifyProperties();
        p.setDefaultChannel("fake");
        return p;
    }

    private static NotifyService service(NotifyProperties p, NotifyTargetResolver r, FakeNotifier n) {
        return new NotifyService(p, r, Collections.emptyList(), Collections.singletonList(n));
    }

    @Test
    void 前缀与正文按单个空格拼接() {
        NotifyMessage m = NotifyMessage.to("t").prefix("[标签] 2026-09-20 13:00:00").content("正文").build();
        assertEquals("[标签] 2026-09-20 13:00:00 正文", m.render());
    }

    @Test
    void 前缀为空时不留多余空格() {
        assertEquals("正文", NotifyMessage.to("t").content("正文").build().render());
        assertEquals("正文", NotifyMessage.to("t").prefix("").content("正文").build().render());
    }

    @Test
    void 正常投递会被记录并解析出地址() {
        FakeNotifier fake = new FakeNotifier();
        NotifyService svc = service(props(), (ch, t) -> "https://example/" + t, fake);
        assertTrue(svc.send("system", "[T] t", "hello"));
        assertEquals(Arrays.asList("[T] t hello"), fake.sent);
        assertEquals("https://example/system", fake.lastEndpoint.get());
    }

    @Test
    void 总开关关闭时一条都不发() {
        FakeNotifier fake = new FakeNotifier();
        NotifyProperties p = props();
        p.setEnabled(false);
        NotifyService svc = service(p, NotifyTargetResolver.identity(), fake);
        assertFalse(svc.send("t", "x"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void 目标解析不出来时不发送() {
        FakeNotifier fake = new FakeNotifier();
        NotifyService svc = service(props(), (ch, t) -> "", fake);
        assertFalse(svc.send("nobody", "x"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void 没有对应通道时报失败并记日志() {
        FakeNotifier fake = new FakeNotifier();
        NotifyService svc = service(props(), NotifyTargetResolver.identity(), fake);
        assertFalse(svc.send(NotifyMessage.to("t").channel("wecom").content("x").build()));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void 静默时段拦截普通消息但放行urgent() {
        FakeNotifier fake = new FakeNotifier();
        NotifyProperties p = props();
        p.setEnforceAllowedWindows(true);
        p.setAllowedWindows("00:00-00:01");
        p.setDaysOfWeek("MO,TU,WE,TH,FR,SA,SU");
        p.setZone("Asia/Shanghai");
        NotifyService svc = new NotifyService(p, NotifyTargetResolver.identity(),
                Collections.singletonList(new AllowedWindowsGate(p)),
                Collections.singletonList(fake));
        assertFalse(svc.send("t", "普通消息"));
        assertTrue(svc.sendUrgent("t", null, "人触发的消息"));
        assertEquals(Arrays.asList("人触发的消息"), fake.sent);
    }

    @Test
    void 闸门抛异常时按放行处理不堵死通知() {
        FakeNotifier fake = new FakeNotifier();
        NotifyService svc = new NotifyService(props(), NotifyTargetResolver.identity(),
                Collections.singletonList(msg -> {
                    throw new IllegalStateException("boom");
                }),
                Collections.singletonList(fake));
        assertTrue(svc.send("t", "x"));
        assertEquals(1, fake.sent.size());
    }

    @Test
    void 多个通道按channel分别路由() {
        FakeNotifier a = new FakeNotifier();
        AtomicInteger bCount = new AtomicInteger();
        Notifier b = new Notifier() {
            @Override
            public String channel() {
                return "other";
            }

            @Override
            public NotifyResult send(String endpoint, NotifyMessage message) {
                bCount.incrementAndGet();
                return NotifyResult.ok();
            }
        };
        NotifyService svc = new NotifyService(props(), NotifyTargetResolver.identity(),
                Collections.emptyList(), Arrays.asList(a, b));
        svc.send(NotifyMessage.to("t").channel("fake").content("x").build());
        svc.send(NotifyMessage.to("t").channel("other").content("y").build());
        assertEquals(1, a.sent.size());
        assertEquals(1, bCount.get());
    }

    // ---------------- 时段判断 ----------------

    @Test
    void 时段判断_落在窗口内为真() {
        Set<DayOfWeek> days = EnumSet.allOf(DayOfWeek.class);
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 10, 0), "09:15-11:30,13:00-15:00", days));
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 9, 15), "09:15-11:30", days));
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 11, 30), "09:15-11:30", days));
        assertFalse(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 12, 0), "09:15-11:30", days));
        assertFalse(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 9, 14), "09:15-11:30", days));
    }

    @Test
    void 星期短码必须被正确解析而不是静默变成不限() {
        // 回归：DayOfWeek.valueOf("MO") 会抛异常，吞掉异常会让「只在工作日发送」失效
        Set<DayOfWeek> weekdays = AllowedWindows.parseDays("MO,TU,WE,TH,FR");
        assertEquals(5, weekdays.size());
        assertTrue(weekdays.contains(DayOfWeek.MONDAY));
        assertTrue(weekdays.contains(DayOfWeek.FRIDAY));
        assertFalse(weekdays.contains(DayOfWeek.SATURDAY));
        // 全名与三字母同样认
        assertEquals(weekdays, AllowedWindows.parseDays("MONDAY,TUE,WEDNESDAY,THU,FRI"));
        // 写错的片段只影响它自己
        assertEquals(1, AllowedWindows.parseDays("XX,SU").size());
    }

    @Test
    void 时段判断_星期过滤生效() {
        // 2026-09-20 是周日
        Set<DayOfWeek> weekdays = AllowedWindows.parseDays("MO,TU,WE,TH,FR");
        assertFalse(AllowedWindows.in(LocalDateTime.of(2026, 9, 20, 10, 0), "09:15-11:30", weekdays));
        // 2026-09-21 是周一
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 10, 0), "09:15-11:30", weekdays));
    }

    @Test
    void 时段判断_配置写坏时放行而不是静默全部() {
        Set<DayOfWeek> days = AllowedWindows.parseDays("MO,TU,WE,TH,FR");
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 3, 0), "", days));
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 3, 0), "乱写的", days));
        assertTrue(AllowedWindows.in(LocalDateTime.of(2026, 9, 21, 3, 0), null, null));
    }

    // ---------------- 文本工具 ----------------

    @Test
    void JSON转义覆盖引号反斜杠换行与控制字符() {
        assertEquals("\"a\\\"b\"", NotifyText.json("a\"b"));
        assertEquals("\"a\\\\b\"", NotifyText.json("a\\b"));
        assertEquals("\"a\\nb\"", NotifyText.json("a\nb"));
        assertEquals("\"\"", NotifyText.json(null));
        assertTrue(NotifyText.json("a\u0001b").contains("\\u0001"));
    }

    @Test
    void 关键词标签按有无主机降级() {
        assertEquals("[智能盯盘]", NotifyText.keywordTag("智能盯盘", null));
        assertEquals("[智能盯盘-1.2.3.4]", NotifyText.keywordTag("智能盯盘", "1.2.3.4"));
        assertEquals("", NotifyText.keywordTag("  ", "1.2.3.4"));
    }

    @Test
    void 地址打码隐藏query里的凭据() {
        String masked = NotifyText.maskEndpoint("https://oapi.example.com/robot/send?access_token=SECRET");
        assertFalse(masked.contains("SECRET"));
        assertTrue(masked.startsWith("https://oapi.example.com/robot/send"));
    }
}
