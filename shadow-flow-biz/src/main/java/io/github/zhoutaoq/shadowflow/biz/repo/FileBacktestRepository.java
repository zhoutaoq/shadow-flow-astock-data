package io.github.zhoutaoq.shadowflow.biz.repo;

import io.github.zhoutaoq.shadowflow.biz.model.Backtest;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestDaily;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestResult;
import io.github.zhoutaoq.shadowflow.biz.model.BacktestTrade;
import io.github.zhoutaoq.shadowflow.biz.model.StockKlineDaily;
import io.github.zhoutaoq.shadowflow.biz.store.DataDir;
import io.github.zhoutaoq.shadowflow.biz.store.JsonFileIo;
import io.github.zhoutaoq.shadowflow.biz.store.StoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 无数据库模式：回测落 {@code ./data/backtest/bt-{id}.json}，一次回测一个文件。
 *
 * <p>启动时把整个目录载入内存（回测是主流程，必须能回看），之后所有读写走内存、写穿到文件。
 * 文件里存主记录 + 每日快照 + 交易明细，<b>不存K线</b> —— K线按设计不落盘，
 * 回看时由 {@link KlineRepository} 现取（与数据库模式「回看时反查日K表」是同一个动作）。
 *
 * <p>保留条数受 {@code shadowflow.store.backtest-keep} 限制，超出淘汰最旧的，避免目录无限膨胀。
 */
@Repository
@ConditionalOnProperty(name = "shadowflow.db.enabled", havingValue = "false", matchIfMissing = true)
public class FileBacktestRepository implements BacktestRepository {

    private static final Logger log = LoggerFactory.getLogger(FileBacktestRepository.class);

    private static final String DIR = "backtest";
    private static final String PREFIX = "bt-";
    private static final String SUFFIX = ".json";
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Path dir;
    private final int keep;
    private final KlineRepository klineRepository;

    /** id → 回测结果（含每日快照与交易明细）；TreeMap 天然按 id 升序，列表倒序遍历即可 */
    private final TreeMap<Long, BacktestResult> rows = new TreeMap<>();

    public FileBacktestRepository(DataDir dataDir, StoreProperties props, KlineRepository klineRepository) {
        this.dir = dataDir.dir(DIR);
        this.keep = props.getBacktestKeep() > 0 ? props.getBacktestKeep() : 500;
        this.klineRepository = klineRepository;
    }

    @PostConstruct
    public synchronized void init() {
        int loaded = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, PREFIX + "*" + SUFFIX)) {
            for (Path p : stream) {
                Long id = parseId(p.getFileName().toString());
                if (id == null) {
                    continue;
                }
                BacktestResult r = JsonFileIo.read(p, BacktestResult.class);
                if (r == null || r.getBacktest() == null) {
                    log.warn("[backtest] 回测文件解析失败，已跳过 {}", p.getFileName());
                    continue;
                }
                r.getBacktest().setId(id);
                if (r.getDailies() == null) {
                    r.setDailies(new ArrayList<>());
                }
                if (r.getTrades() == null) {
                    r.setTrades(new ArrayList<>());
                }
                rows.put(id, r);
                loaded++;
            }
        } catch (Exception e) {
            log.warn("[backtest] 回测目录读取失败 {}：{}", dir, e.getMessage());
        }
        trim();
        log.info("[backtest] 无数据库模式：{} 已载入 {} 条历史回测（上限 {} 条）", dir, loaded, keep);
    }

    @Override
    public boolean persistent() {
        return true;
    }

    @Override
    public synchronized long insert(Backtest bt) {
        if (bt == null) {
            throw new IllegalArgumentException("回测记录不能为空");
        }
        long id = rows.isEmpty() ? 1L : rows.lastKey() + 1;
        bt.setId(id);
        if (bt.getCreateTime() == null || bt.getCreateTime().trim().isEmpty()) {
            bt.setCreateTime(LocalDateTime.now().format(DF));
        }
        BacktestResult r = new BacktestResult();
        r.setBacktest(bt);
        r.setDailies(new ArrayList<>());
        r.setTrades(new ArrayList<>());
        rows.put(id, r);
        persist(id);
        trim();
        return id;
    }

    @Override
    public synchronized void saveDailies(long backtestId, List<BacktestDaily> dailies) {
        BacktestResult r = rows.get(backtestId);
        if (r == null || dailies == null) {
            return;
        }
        r.setDailies(new ArrayList<>(dailies));
        persist(backtestId);
    }

    @Override
    public synchronized void saveTrades(long backtestId, List<BacktestTrade> trades) {
        BacktestResult r = rows.get(backtestId);
        if (r == null || trades == null) {
            return;
        }
        r.setTrades(new ArrayList<>(trades));
        persist(backtestId);
    }

    @Override
    public synchronized List<Backtest> list(int limit) {
        int lim = limit <= 0 ? 50 : Math.min(limit, 500);
        List<Backtest> out = new ArrayList<>();
        List<Long> ids = new ArrayList<>(rows.descendingKeySet());
        for (Long id : ids) {
            if (out.size() >= lim) {
                break;
            }
            Backtest bt = rows.get(id).getBacktest();
            if (bt != null) {
                out.add(bt);
            }
        }
        return out;
    }

    @Override
    public synchronized BacktestResult detail(long id) {
        BacktestResult src = rows.get(id);
        if (src == null) {
            return null;
        }
        Backtest bt = src.getBacktest();
        BacktestResult out = new BacktestResult();
        out.setBacktest(bt);
        out.setDailies(src.getDailies() == null ? new ArrayList<>() : new ArrayList<>(src.getDailies()));
        out.setTrades(src.getTrades() == null ? new ArrayList<>() : new ArrayList<>(src.getTrades()));
        // K线不落盘，回看时按回测区间现取（无数据库模式下即向行情数据服务取一次）
        List<StockKlineDaily> klines = new ArrayList<>();
        if (bt != null && bt.getStockCode() != null && bt.getStartDate() != null && bt.getEndDate() != null) {
            try {
                List<StockKlineDaily> fetched = klineRepository.list(
                        bt.getMarket() == null ? "stock" : bt.getMarket(),
                        bt.getStockCode(), bt.getStartDate(), bt.getEndDate());
                if (fetched != null) {
                    klines = fetched;
                }
            } catch (Exception e) {
                // K线取不到不影响回测主体：页面仍能看每日快照与交易明细
                log.warn("[backtest] 回测 {} 的区间K线现取失败：{}", id, e.getMessage());
            }
        }
        out.setKlines(klines);
        return out;
    }

    @Override
    public synchronized void delete(long id) {
        BacktestResult removed = rows.remove(id);
        if (removed == null) {
            return;
        }
        JsonFileIo.delete(fileOf(id));
    }

    /** 写单个回测文件；K线不入文件 */
    private void persist(long id) {
        BacktestResult r = rows.get(id);
        if (r == null) {
            return;
        }
        List<StockKlineDaily> klines = r.getKlines();
        r.setKlines(Collections.emptyList());
        try {
            JsonFileIo.write(fileOf(id), r);
        } finally {
            r.setKlines(klines == null ? new ArrayList<>() : klines);
        }
    }

    /** 超出保留上限时淘汰最旧的 */
    private void trim() {
        while (rows.size() > keep) {
            Map.Entry<Long, BacktestResult> oldest = rows.firstEntry();
            if (oldest == null) {
                return;
            }
            rows.remove(oldest.getKey());
            JsonFileIo.delete(fileOf(oldest.getKey()));
            log.info("[backtest] 历史回测超过上限 {} 条，已淘汰最旧的 #{}", keep, oldest.getKey());
        }
    }

    private Path fileOf(long id) {
        return dir.resolve(PREFIX + id + SUFFIX);
    }

    private static Long parseId(String fileName) {
        if (fileName == null || !fileName.startsWith(PREFIX) || !fileName.endsWith(SUFFIX)) {
            return null;
        }
        String mid = fileName.substring(PREFIX.length(), fileName.length() - SUFFIX.length());
        try {
            return Long.valueOf(mid);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
