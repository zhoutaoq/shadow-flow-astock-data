package io.github.zhoutaoq.shadowflow.biz.news;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时资讯的两块数据：热门股排行 + 板块涨跌幅。
 *
 * <p>设计上跟着 {@code MarketIndexService} 那几个已跑通的约定走，因为场景完全一样 ——
 * 页面轮询公开接口：
 * <ol>
 *   <li><b>缓存 2 秒</b>：v1.5.8 起页面改成一分钟一轮，这个窗口不再是为「追上页面」，
 *       而是把「多人同时看 / 多开标签页 / 手点立即刷新」并发的上游请求压成最多每 2 秒一次；</li>
 *   <li><b>失败日志按分钟节流</b>：上游不通时若每轮都打，一天能刷出几万行把 app.log 撑爆；</li>
 *   <li><b>有旧值就用旧值</b>：一次抖动不该让页面从有数变成空白，
 *       只有从来没有取到过才把失败原因透给前端。</li>
 * </ol>
 *
 * <p>多加了一条：<b>各平台并发取</b>。三家串行的话，一旦某家慢或走多宿主回退，
 * 整轮耗时是各家之和；并发之后整轮约等于最慢那一家。
 *
 * <p>平台能力不对等（同花顺没有板块接口、通达信没有可用公开接口），所以这里<b>只返回
 * 有该能力的平台</b>，页面就不会出现长期空着的卡片，也不会拿别家数据顶替。
 */
@Service
public class BoardService {

    private static final Logger log = LoggerFactory.getLogger(BoardService.class);

    /** 缓存时长：页面一分钟一轮，这个窗口只用来挡并发（多人同时看 / 连点刷新） */
    private static final long CACHE_MS = 2_000L;
    /** 单平台取数超时：超过就不再等它，免得拖住整轮 */
    private static final long GROUP_TIMEOUT_MS = 4_000L;
    /** 失败日志节流：同一平台同一块一分钟最多报一条 */
    private static final long WARN_THROTTLE_MS = 60_000L;

    /** 卡片展示顺序，与需求里列平台的顺序一致；不在表里的排最后 */
    private static final List<String> ORDER = Arrays.asList("eastmoney", "sina", "tonghuashun");

    /** 单次取数条数上限：三家接口都支持，页面也只展示前 20 条 */
    public static final int MAX_LIMIT = 50;
    private static final int DEFAULT_LIMIT = 20;

    private final List<BoardProvider> providers;
    private final Map<String, Slot<HotStock>> hotSlots = new ConcurrentHashMap<>();
    private final Map<String, Slot<SectorRow>> sectorSlots = new ConcurrentHashMap<>();
    private final ExecutorService pool;

    public BoardService(List<BoardProvider> providers) {
        List<BoardProvider> sorted = new ArrayList<>(providers);
        // Spring 注入 List 的顺序不保证，按 ORDER 摆一次，卡片顺序才不会在重启后跳
        sorted.sort((a, b) -> Integer.compare(orderOf(a.id()), orderOf(b.id())));
        this.providers = sorted;
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "board-" + seq.getAndIncrement());
                // 守护线程：应用停的时候不必等这几个上游请求
                t.setDaemon(true);
                return t;
            }
        };
        this.pool = Executors.newFixedThreadPool(Math.max(2, sorted.size()), tf);
        log.info("[board] 实时资讯数据源 {} 个：{}", sorted.size(), labels());
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
    }

    /** 热门股排行：只有声明支持该能力的平台会出现在结果里 */
    public List<BoardGroup<HotStock>> hotStocks(Integer limit) {
        final int n = normalizeLimit(limit);
        List<BoardProvider> targets = new ArrayList<>();
        for (BoardProvider p : providers) {
            if (p.supportsHotStocks()) {
                targets.add(p);
            }
        }
        return collectHot(targets, n);
    }

    /** 板块涨跌幅（行业口径）：只有声明支持该能力的平台会出现在结果里 */
    public List<BoardGroup<SectorRow>> sectors(Integer limit) {
        final int n = normalizeLimit(limit);
        List<BoardProvider> targets = new ArrayList<>();
        for (BoardProvider p : providers) {
            if (p.supportsSectors()) {
                targets.add(p);
            }
        }
        return collectSectors(targets, n);
    }

    /** 平台清单与能力，供页面判断要不要渲染某块 */
    public List<Map<String, Object>> platforms() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BoardProvider p : providers) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", p.id());
            m.put("label", p.label());
            m.put("hotStocks", p.supportsHotStocks());
            m.put("sectors", p.supportsSectors());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------ 并发取数

    /**
     * 并发跑各平台，按 targets 的顺序收集结果。
     *
     * <p>用两个等长列表按下标配对（Spring 的 List 注入顺序不保证，所以 targets 已经排过序）。
     * 某个平台超时或抛异常时，只为它生成一个失败占位，不影响其它平台。
     */
    private List<BoardGroup<HotStock>> collectHot(List<BoardProvider> targets, int limit) {
        List<Future<BoardGroup<HotStock>>> futures = new ArrayList<>(targets.size());
        for (final BoardProvider p : targets) {
            futures.add(pool.submit(new Callable<BoardGroup<HotStock>>() {
                @Override
                public BoardGroup<HotStock> call() {
                    return hotOf(p, limit);
                }
            }));
        }
        List<BoardGroup<HotStock>> out = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            BoardProvider p = targets.get(i);
            try {
                out.add(futures.get(i).get(GROUP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                futures.get(i).cancel(true);
                out.add(slowFail(p, hotSlotsFor(p), e));
            }
        }
        return out;
    }

    private List<BoardGroup<SectorRow>> collectSectors(List<BoardProvider> targets, int limit) {
        List<Future<BoardGroup<SectorRow>>> futures = new ArrayList<>(targets.size());
        for (final BoardProvider p : targets) {
            futures.add(pool.submit(new Callable<BoardGroup<SectorRow>>() {
                @Override
                public BoardGroup<SectorRow> call() {
                    return sectorsOf(p, limit);
                }
            }));
        }
        List<BoardGroup<SectorRow>> out = new ArrayList<>(targets.size());
        for (int i = 0; i < targets.size(); i++) {
            BoardProvider p = targets.get(i);
            try {
                out.add(futures.get(i).get(GROUP_TIMEOUT_MS, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                futures.get(i).cancel(true);
                out.add(slowFail(p, sectorSlotsFor(p), e));
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 单平台（带缓存）

    private BoardGroup<HotStock> hotOf(BoardProvider p, int limit) {
        Slot<HotStock> slot = hotSlotsFor(p);
        long now = System.currentTimeMillis();
        if (now - slot.at < CACHE_MS) {
            return BoardGroup.ok(p.id(), p.label(), slot.rows);
        }
        try {
            List<HotStock> rows = p.hotStocks(limit);
            slot.at = now;
            if (rows != null && !rows.isEmpty()) {
                slot.rows = rows;
                return BoardGroup.ok(p.id(), p.label(), rows);
            }
            // 拿到空（非交易时段可能全为 0，但条数一般不为空）：沿用旧值，别把页面清空
            return BoardGroup.ok(p.id(), p.label(), slot.rows);
        } catch (Exception e) {
            slot.at = now;
            warnThrottled(p, "热门股", e, slot);
            return slot.rows.isEmpty()
                    ? BoardGroup.fail(p.id(), p.label(), brief(e))
                    : BoardGroup.ok(p.id(), p.label(), slot.rows);
        }
    }

    private BoardGroup<SectorRow> sectorsOf(BoardProvider p, int limit) {
        Slot<SectorRow> slot = sectorSlotsFor(p);
        long now = System.currentTimeMillis();
        if (now - slot.at < CACHE_MS) {
            return BoardGroup.ok(p.id(), p.label(), slot.rows);
        }
        try {
            List<SectorRow> rows = p.sectors(limit);
            slot.at = now;
            if (rows != null && !rows.isEmpty()) {
                slot.rows = rows;
                return BoardGroup.ok(p.id(), p.label(), rows);
            }
            return BoardGroup.ok(p.id(), p.label(), slot.rows);
        } catch (Exception e) {
            slot.at = now;
            warnThrottled(p, "板块", e, slot);
            return slot.rows.isEmpty()
                    ? BoardGroup.fail(p.id(), p.label(), brief(e))
                    : BoardGroup.ok(p.id(), p.label(), slot.rows);
        }
    }

    /** 整体超时：同上，有旧值给旧值，没有才标失败 */
    private <T> BoardGroup<T> slowFail(BoardProvider p, Slot<T> slot, Exception e) {
        warnThrottled(p, "取数", e, slot);
        return slot.rows.isEmpty()
                ? BoardGroup.fail(p.id(), p.label(), "取数超时")
                : BoardGroup.ok(p.id(), p.label(), slot.rows);
    }

    private Slot<HotStock> hotSlotsFor(BoardProvider p) {
        return hotSlots.computeIfAbsent(p.id(), k -> new Slot<>());
    }

    private Slot<SectorRow> sectorSlotsFor(BoardProvider p) {
        return sectorSlots.computeIfAbsent(p.id(), k -> new Slot<>());
    }

    // ------------------------------------------------------------ 小工具

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private int orderOf(String id) {
        int i = ORDER.indexOf(id);
        return i < 0 ? ORDER.size() : i;
    }

    private String labels() {
        StringBuilder sb = new StringBuilder();
        for (BoardProvider p : providers) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(p.label()).append('(');
            if (p.supportsHotStocks()) {
                sb.append("热门股");
            }
            if (p.supportsSectors()) {
                sb.append(p.supportsHotStocks() ? "+板块" : "板块");
            }
            sb.append(')');
        }
        return sb.toString();
    }

    private void warnThrottled(BoardProvider p, String what, Exception e, Slot<?> slot) {
        long now = System.currentTimeMillis();
        if (now - slot.warnAt < WARN_THROTTLE_MS) {
            return;
        }
        slot.warnAt = now;
        log.warn("[board] {} {}取数失败 err={}", p.label(), what, brief(e));
    }

    private static String brief(Exception e) {
        String msg = e == null ? null : e.getMessage();
        if (msg == null || msg.trim().isEmpty()) {
            msg = e == null ? "未知错误" : e.getClass().getSimpleName();
        }
        return msg.length() > 120 ? msg.substring(0, 120) : msg;
    }

    /** 单个平台单块数据的缓存槽：上一次的数据、取数时间、上次告警时间 */
    private static final class Slot<T> {
        volatile List<T> rows = new ArrayList<>();
        volatile long at;
        volatile long warnAt;
    }
}
