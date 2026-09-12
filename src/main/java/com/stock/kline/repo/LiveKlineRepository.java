package com.stock.kline.repo;

import com.stock.kline.client.RawKline;
import com.stock.kline.client.WaizaoKlineClient;
import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.ApiCallLog;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.support.KlineWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 无数据库模式：日K直接向供应商取，<b>现取现用、不落盘</b>。
 *
 * <p>K线是设计里明确点名「不持久化」的数据：按设计约定，无数据库模式下它不进 {@code ./data}，
 * 而是每次需要时向供应商现取。代价是回看历史回测会再调一次接口（按次计费），
 * 换来的是不用维护一份随股票数和时间无限膨胀的本地数据。
 *
 * <p>预热段和回测区间合成一次请求 —— 供应商单次请求支持任意长日期区间，
 * 按次计费，拆成两次只会多花钱。
 */
@Repository
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "false", matchIfMissing = true)
public class LiveKlineRepository implements KlineRepository {

    private static final Logger log = LoggerFactory.getLogger(LiveKlineRepository.class);

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 预热段按「交易日 × 2 + 40 自然日」往前推，覆盖周末与长假 */
    private static final int WARMUP_CALENDAR_FACTOR = 2;
    private static final int WARMUP_CALENDAR_BUFFER = 40;

    private final WaizaoKlineClient client;
    private final WaizaoProperties props;

    public LiveKlineRepository(WaizaoKlineClient client, WaizaoProperties props) {
        this.client = client;
        this.props = props;
    }

    @Override
    public KlineWindow load(String market, String code, String start, String end, int warmup) {
        LocalDate s = LocalDate.parse(start, DF);
        LocalDate e = LocalDate.parse(end, DF);
        long span = ChronoUnit.DAYS.between(s, e);
        if (span > props.getMaxSpanDays()) {
            throw new IllegalArgumentException("查询跨度 " + (span + 1) + " 个自然日，"
                    + "无数据库模式上限为 " + props.getMaxSpanDays() + " 天");
        }
        int backDays = warmup > 0 ? warmup * WARMUP_CALENDAR_FACTOR + WARMUP_CALENDAR_BUFFER : 0;
        String fetchStart = backDays > 0 ? s.minusDays(backDays).format(DF) : start;

        List<StockKlineDaily> rows = fetch(market, code, fetchStart, end);
        rows.sort(Comparator.comparing(StockKlineDaily::getTradeDate));

        KlineWindow w = new KlineWindow();
        int preSize = 0;
        for (StockKlineDaily k : rows) {
            if (k.getTradeDate().compareTo(start) < 0) {
                preSize++;
            }
        }
        w.getRows().addAll(rows);
        w.setPreSize(preSize);
        return w;
    }

    @Override
    public List<String> missing(String market, String code, String start, String end) {
        // 数据现取，取不到就是供应商没有，补也没用
        return Collections.emptyList();
    }

    @Override
    public void prepareOne(String market, String code, String date) {
        // 无数据库模式没有本地存储，不需要预先补数据
    }

    /** 数据不在本地，无从谈"已入库跨度"，页面改由用户直接给起止日期 */
    @Override
    public Map<String, Object> span(String market, String code) {
        Map<String, Object> data = new HashMap<>();
        data.put("minDate", null);
        data.put("maxDate", null);
        data.put("count", 0L);
        return data;
    }

    @Override
    public List<StockKlineDaily> list(String market, String code, String start, String end) {
        return fetch(market, code, start, end);
    }

    @Override
    public int save(List<RawKline> rows) {
        // K线在无数据库模式下不落盘：既没有库，也不写 ./data
        if (rows != null && !rows.isEmpty()) {
            log.debug("[kline] 无数据库模式：{} 行日K现取现用，不落盘", rows.size());
        }
        return 0;
    }

    private List<StockKlineDaily> fetch(String market, String code, String start, String end) {
        List<RawKline> raw;
        try {
            raw = client.fetch(market, code, start, end, ApiCallLog.SCENE_BATCH);
        } catch (IOException ex) {
            throw new IllegalStateException("从供应商取日K失败: " + ex.getMessage());
        }
        List<StockKlineDaily> rows = new ArrayList<>(raw.size());
        for (RawKline r : raw) {
            if (r.getTradeDate() == null) {
                continue;
            }
            rows.add(convert(r, market, code));
        }
        return rows;
    }

    private static StockKlineDaily convert(RawKline r, String market, String code) {
        StockKlineDaily k = new StockKlineDaily();
        k.setStockCode(StringUtils.hasText(r.getCode()) ? r.getCode() : code);
        k.setMarket(StringUtils.hasText(r.getSymbol()) ? r.getSymbol() : market);
        k.setTradeDate(r.getTradeDate());
        k.setOpenPrice(r.getOpenPrice());
        k.setClosePrice(r.getClosePrice());
        k.setHighPrice(r.getHighPrice());
        k.setLowPrice(r.getLowPrice());
        k.setVolume(r.getVolume());
        k.setAmount(r.getAmount());
        k.setPreClose(r.getPreClose());
        return k;
    }
}
