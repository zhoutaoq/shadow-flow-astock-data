package com.stock.kline.service;

import com.stock.kline.model.HomeHero;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.model.SysConfig;
import com.stock.kline.repo.KlineRepository;
import com.stock.kline.repo.SysConfigRepository;
import com.stock.kline.support.AShareCodes;
import com.stock.kline.support.StockDirectory;
import com.stock.kline.web.StockListController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页品牌带背景行情的配置与取数。
 *
 * <p>背景画的是一段真实日K，标的与区间由超管在「系统配置」页维护，
 * 存在系统级配置行（{@code ownerId = 0}，场景 {@code home}）里，
 * 站点上所有人都看同一份 —— 与按用户隔离的接口令牌不是一回事。
 *
 * <p>没配过时 {@link #current()} 返回 {@code configured = false}，
 * 首页继续用它自带的默认背景，本站不为「画个背景」额外发一次取数请求。
 *
 * <p>配过之后行情缓存在内存里（配置变更即失效），避免每个访客打开首页都去取一次数：
 * 无数据库模式下取一次就是一次计费的供应商调用，不能跟着访问量走。
 */
@Service
public class HomeHeroService {

    private static final Logger log = LoggerFactory.getLogger(HomeHeroService.class);

    /** 主机上默认展示的那段行情：长电科技 600584，2026 年 3–6 月 */
    public static final String DEFAULT_CODE = "600584";
    public static final String DEFAULT_NAME = "长电科技";
    public static final String DEFAULT_START = "2026-03-02";
    public static final String DEFAULT_END = "2026-06-30";

    /** 背景区间上限（自然日）：背景要的是一段看得懂的走势，一年足够 */
    public static final int MAX_SPAN_DAYS = 365;

    /** 配置落点：系统级 + 场景 home 下的一组键 */
    private static final String SCENE = "home";
    private static final String KEY_CODE = "hero.code";
    private static final String KEY_NAME = "hero.name";
    private static final String KEY_START = "hero.startDate";
    private static final String KEY_END = "hero.endDate";
    private static final String REMARK = "首页背景行情";

    /** 系统只做沪深A股，行情接口的市场参数恒定是这个值 */
    private static final String MARKET = "stock";

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 行情缓存有效期：区间一般都在过去，没必要时时重取；配置一改立即失效 */
    private static final long CACHE_TTL_MS = 5 * 60_000L;

    private final SysConfigRepository configs;
    private final KlineRepository klines;
    private final StockDirectory directory;

    /** 最近一次取到的背景行情，与配置一一对应；取不到时也记着，免得反复去撞失败 */
    private volatile CachedBars cache;

    public HomeHeroService(SysConfigRepository configs, KlineRepository klines, StockDirectory directory) {
        this.configs = configs;
        this.klines = klines;
        this.directory = directory;
    }

    /** 当前生效的背景行情（含行情本身）；未配置或取不到数据时 bars 为空 */
    public HomeHero current() {
        HomeHero hero = read();
        if (!hero.isConfigured()) {
            return hero;
        }
        try {
            hero.setBars(barsOf(hero.getCode(), hero.getStartDate(), hero.getEndDate()));
        } catch (RuntimeException e) {
            // 首页是匿名入口，取不到行情绝不能让它报错：留空由页面回落到自带背景
            log.warn("[home-hero] {} 的背景行情取数失败：{}", hero.getCode(), rootMsg(e));
            hero.setBars(null);
        }
        return hero;
    }

    /**
     * 保存背景行情配置。
     *
     * <p>先把这段区间的行情取回来再落配置：取不到就整体不生效，
     * 免得首页出现「标的写着 A、画面还是 B」或者干脆空白。
     *
     * @throws IllegalArgumentException 入参不合法或该区间取不到行情
     */
    public HomeHero save(String code, String name, String startDate, String endDate) {
        String c = trim(code);
        String start = trim(startDate);
        String end = trim(endDate);
        if (!AShareCodes.isAShare(c)) {
            throw new IllegalArgumentException("股票代码需要是 6 位沪深A股代码");
        }
        if (!isDate(start) || !isDate(end)) {
            throw new IllegalArgumentException("日期格式需要是 yyyy-MM-dd");
        }
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("起始日期不能晚于结束日期");
        }
        if (spanDays(start, end) > MAX_SPAN_DAYS) {
            throw new IllegalArgumentException("日期区间最长一年（" + MAX_SPAN_DAYS + " 天）");
        }
        String n = trim(name);
        if (!StringUtils.hasText(n)) {
            n = nameOf(c);
        }

        // 先取数：取不到就不落配置
        List<double[]> bars = fetch(c, start, end);
        cache = new CachedBars(c, start, end, bars, System.currentTimeMillis());

        saveRow(KEY_CODE, c);
        saveRow(KEY_NAME, n);
        saveRow(KEY_START, start);
        saveRow(KEY_END, end);
        log.info("[home-hero] 首页背景已更新为 {} {} {} ~ {}（{} 个交易日）", n, c, start, end, bars.size());

        HomeHero hero = new HomeHero();
        hero.setConfigured(true);
        hero.setCode(c);
        hero.setName(n);
        hero.setStartDate(start);
        hero.setEndDate(end);
        hero.setUpdateTime(nowText());
        hero.setBars(bars);
        return hero;
    }

    /** 恢复默认：删掉这组配置行，首页回到自带的那段背景 */
    public void reset() {
        for (SysConfig row : configs.list(SysConfig.SYSTEM_OWNER)) {
            if (SCENE.equals(row.getConfigScene()) && row.getId() != null) {
                configs.delete(SysConfig.SYSTEM_OWNER, row.getId());
            }
        }
        cache = null;
        log.info("[home-hero] 首页背景已恢复默认");
    }

    /** 读配置行；四项缺任意一项都当作「还没配过」 */
    private HomeHero read() {
        HomeHero hero = new HomeHero();
        String code = trim(configs.get(SCENE, KEY_CODE));
        String start = trim(configs.get(SCENE, KEY_START));
        String end = trim(configs.get(SCENE, KEY_END));
        if (!StringUtils.hasText(code) || !isDate(start) || !isDate(end)) {
            hero.setConfigured(false);
            hero.setCode(DEFAULT_CODE);
            hero.setName(DEFAULT_NAME);
            hero.setStartDate(DEFAULT_START);
            hero.setEndDate(DEFAULT_END);
            return hero;
        }
        hero.setConfigured(true);
        hero.setCode(code);
        String name = trim(configs.get(SCENE, KEY_NAME));
        hero.setName(StringUtils.hasText(name) ? name : nameOf(code));
        hero.setStartDate(start);
        hero.setEndDate(end);
        return hero;
    }

    private void saveRow(String key, String value) {
        SysConfig cfg = new SysConfig();
        cfg.setOwnerId(SysConfig.SYSTEM_OWNER);
        cfg.setConfigScene(SCENE);
        cfg.setConfigKey(key);
        cfg.setConfigValue(value);
        cfg.setRemark(REMARK);
        configs.save(cfg);
    }

    private String nowText() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 标的名称：页面没带名称时查名录，查不到就用代码顶上 */
    private String nameOf(String code) {
        try {
            for (StockDirectory.Item it : directory.search(code, 5)) {
                if (code.equals(it.getCode())) {
                    return it.getName();
                }
            }
        } catch (RuntimeException e) {
            log.warn("[home-hero] 名录里没查到 {} 的名称：{}", code, rootMsg(e));
        }
        return code;
    }

    /** 带缓存取行情：同一份配置 5 分钟内只取一次 */
    private List<double[]> barsOf(String code, String start, String end) {
        CachedBars c = cache;
        if (c != null && c.sameAs(code, start, end)
                && System.currentTimeMillis() - c.at < CACHE_TTL_MS) {
            return c.bars;
        }
        List<double[]> bars;
        try {
            bars = fetch(code, start, end);
        } catch (RuntimeException e) {
            // 失败也进缓存：首页访问量远大于配置变更频率，不能让它反复去撞同一个错
            cache = new CachedBars(code, start, end, null, System.currentTimeMillis());
            throw e;
        }
        cache = new CachedBars(code, start, end, bars, System.currentTimeMillis());
        return bars;
    }

    /** 取区间行情；取不到直接抛，由调用方决定是静默回落还是提示用户 */
    private List<double[]> fetch(String code, String start, String end) {
        List<StockKlineDaily> rows;
        try {
            rows = klines.load(MARKET, code, start, end, 0).getRows();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("取行情失败：" + rootMsg(e));
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("这个区间没有取到行情数据，检查一下标的与日期");
        }
        List<double[]> bars = new ArrayList<>(rows.size());
        for (StockKlineDaily r : rows) {
            if (r == null || r.getClosePrice() == null || r.getOpenPrice() == null) {
                continue;
            }
            bars.add(new double[]{
                    d(r.getOpenPrice()),
                    d(r.getHighPrice() == null ? r.getClosePrice() : r.getHighPrice()),
                    d(r.getLowPrice() == null ? r.getClosePrice() : r.getLowPrice()),
                    d(r.getClosePrice()),
                    r.getVolume() == null ? 0d : r.getVolume().doubleValue()
            });
        }
        if (bars.isEmpty()) {
            throw new IllegalArgumentException("这个区间的行情数据不完整，换一段日期试试");
        }
        return bars;
    }

    private static double d(BigDecimal v) {
        return v == null ? 0d : v.doubleValue();
    }

    private static boolean isDate(String v) {
        if (!StringUtils.hasText(v)) {
            return false;
        }
        try {
            LocalDate.parse(v.trim(), DF);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long spanDays(String start, String end) {
        return ChronoUnit.DAYS.between(LocalDate.parse(start, DF), LocalDate.parse(end, DF)) + 1;
    }

    private static String trim(String v) {
        return v == null ? "" : v.trim();
    }

    private static String rootMsg(Throwable e) {
        return StockListController.rootMsg(e);
    }

    /** 已取到的一段行情 */
    private static final class CachedBars {
        private final String code;
        private final String start;
        private final String end;
        private final List<double[]> bars;
        private final long at;

        private CachedBars(String code, String start, String end, List<double[]> bars, long at) {
            this.code = code;
            this.start = start;
            this.end = end;
            this.bars = bars;
            this.at = at;
        }

        private boolean sameAs(String code, String start, String end) {
            return this.code.equals(code) && this.start.equals(start) && this.end.equals(end);
        }
    }
}
