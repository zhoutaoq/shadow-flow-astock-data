package com.stock.kline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stock.kline.config.WaizaoProperties;
import com.stock.kline.model.Backtest;
import com.stock.kline.model.BacktestCondition;
import com.stock.kline.model.BacktestDaily;
import com.stock.kline.model.BacktestProgress;
import com.stock.kline.model.BacktestResult;
import com.stock.kline.model.BacktestRule;
import com.stock.kline.model.BacktestRunRequest;
import com.stock.kline.model.BacktestTrade;
import com.stock.kline.model.StockKlineDaily;
import com.stock.kline.repo.BacktestRepository;
import com.stock.kline.repo.KlineRepository;
import com.stock.kline.support.KlineWindow;
import com.stock.kline.support.StockDirectory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回测引擎。
 *
 * <p>数据源由 KlineRepository 决定：数据库模式读日K表（缺的日期先同步再读），
 * 无数据库模式向供应商现取；结果落点由 BacktestRepository 决定：数据库模式落三张表，
 * 无数据库模式落 {@code ./data/backtest/}。两种模式都留存，回测是主流程。
 *
 * <p>执行约定（与需求一致）：
 * <ul>
 *   <li>全仓买卖，不分仓；成交单位为 100 股整数倍</li>
 *   <li>信号在 T 日收盘确认，按 T 日收盘价成交</li>
 *   <li>摩擦成本为单一费率，买入按成交额加价、卖出按成交额减价，各计一次</li>
 *   <li>回测区间之前的多余数据只用于指标预热，不产生买卖信号</li>
 * </ul>
 */
@Service
public class BacktestService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 最小成交单位：100 股 */
    private static final int LOT = 100;
    /** 金额/比率计算精度 */
    private static final int SCALE_MONEY = 4;
    private static final int SCALE_RATE = 8;

    /** 涨跌停策略 1：无视，仍按信号日收盘价成交 */
    private static final int STRATEGY_IGNORE = 1;
    /** 涨跌停策略 2：次日开盘非涨跌停立即成交 */
    private static final int STRATEGY_NEXT_OPEN = 2;
    /** 涨跌停策略 3：次日开盘非涨跌停、且相对信号日收盘涨跌幅达阈值才成交 */
    private static final int STRATEGY_NEXT_COND = 3;

    private final KlineRepository klineRepository;
    private final BacktestRepository backtestRepository;
    private final WaizaoProperties props;
    /** 只用来把股票代码翻译成名称：结果里的「标的」要能直接看出是哪只票 */
    private final StockDirectory directory;
    private final ObjectMapper mapper = new ObjectMapper();
    /**
     * taskId → 执行进度。
     *
     * <p>回测是同步跑完才返回结果的，前端在等待响应期间另开轮询请求读这里看进度，
     * 所以必须用并发容器，否则读侧可能拿到写了一半的状态。
     */
    private final Map<String, BacktestProgress> progressMap = new ConcurrentHashMap<>();

    public BacktestService(KlineRepository klineRepository, BacktestRepository backtestRepository,
                           WaizaoProperties props, StockDirectory directory) {
        this.klineRepository = klineRepository;
        this.backtestRepository = backtestRepository;
        this.props = props;
        this.directory = directory;
    }

    /** 回测结果是否落盘；前端据此决定要不要展示「历史回测」 */
    public boolean persistent() {
        return backtestRepository.persistent();
    }

    // ============ 执行回测 ============

    /** 执行一次回测：校验 → 取数 → 模拟 → 落库 → 返回结果 */
    public BacktestResult run(BacktestRunRequest req) {
        String taskId = req == null ? null : req.getTaskId();
        String code = req == null || req.getStockCode() == null ? null : req.getStockCode().trim();
        String market = req != null && StringUtils.hasText(req.getMarket()) ? req.getMarket().trim() : "stock";
        if (!StringUtils.hasText(code)) {
            throw new IllegalArgumentException("请指定股票代码");
        }
        if (req == null || !StringUtils.hasText(req.getStartDate()) || !StringUtils.hasText(req.getEndDate())) {
            throw new IllegalArgumentException("请指定回测起止时间");
        }
        String start = req.getStartDate().trim();
        String end = req.getEndDate().trim();
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("回测起始日不能晚于结束日");
        }
        // 无数据库模式每次查询都是一次真实请求，跨度必须先卡住再发
        checkSpan(start, end);
        BigDecimal init = req.getInitAmount();
        if (init == null || init.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("账户起始金额必须大于 0");
        }
        BigDecimal fee = req.getFeeRate() == null ? new BigDecimal("0.0001") : req.getFeeRate();
        if (fee.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("手续费滑点不能小于 0");
        }
        String buyErr = req.getBuyRule() == null ? "买入策略不能为空" : req.getBuyRule().validate();
        if (buyErr != null) {
            throw new IllegalArgumentException("买入策略配置有误: " + buyErr);
        }
        String sellErr = req.getSellRule() == null ? "卖出策略不能为空" : req.getSellRule().validate();
        if (sellErr != null) {
            throw new IllegalArgumentException("卖出策略配置有误: " + sellErr);
        }

        // 数据可用性：只判断起止两条；缺哪天先补哪天，补完仍缺才报错
        updateProgress(taskId, BacktestProgress.STAGE_CHECK, 0, 0, "校验日K数据完整性");
        ensureBoundaryData(taskId, code, market, start, end);

        // 取数：预热段 + 回测区间
        int warmup = warmupDays(req.getBuyRule(), req.getSellRule());
        KlineWindow window = klineRepository.load(market, code, start, end, warmup);
        List<StockKlineDaily> all = window.getRows();
        int preSize = window.getPreSize();
        if (all.size() - preSize <= 0) {
            throw new IllegalStateException("回测区间内没有日K数据，请先更新数据");
        }

        // 模拟
        List<BigDecimal> closes = new ArrayList<>(all.size());
        List<BigDecimal> volumes = new ArrayList<>(all.size());
        for (StockKlineDaily k : all) {
            closes.add(k.getClosePrice());
            volumes.add(k.getVolume() == null ? null : new BigDecimal(k.getVolume()));
        }
        Indicator ind = new Indicator(closes, volumes);

        Account acc = new Account(init);
        List<BacktestDaily> dailies = new ArrayList<>();
        List<BacktestTrade> trades = new ArrayList<>();
        int totalDays = all.size() - preSize;
        updateProgress(taskId, BacktestProgress.STAGE_RUN, 0, totalDays, "开始逐日计算");

        // 股票名称顺带用来判 ST（涨跌停阈值不同），查一次两处复用
        String stockName = resolveName(code);
        // 涨跌停阈值：ST 5%、创业/科创 20%、其余 10%，各留 0.5% 容错
        BigDecimal limitTh = limitThreshold(code, stockName);
        Pending pending = null;

        for (int i = preSize; i < all.size(); i++) {
            StockKlineDaily k = all.get(i);

            // 上一日信号约到今天的成交先落地；落地后本日不再产生新信号
            // （A 股 T+1，也避免同一天既买又卖）
            if (pending != null) {
                if (pending.buy) {
                    buy(acc, pending.fill.date, pending.fill.price, fee, trades);
                } else {
                    sell(acc, pending.fill.date, pending.fill.price, fee, trades);
                }
                pending = null;
                // 当天收盘价缺失就没有估值依据，跟下面一样不记快照
                if (k.getClosePrice() != null && k.getClosePrice().compareTo(BigDecimal.ZERO) > 0) {
                    dailies.add(daily(acc, k.getTradeDate(), k.getClosePrice(), init));
                }
                updateProgress(taskId, BacktestProgress.STAGE_RUN, i - preSize + 1, totalDays, null);
                continue;
            }

            BigDecimal close = k.getClosePrice();
            if (close == null || close.compareTo(BigDecimal.ZERO) <= 0) {
                // 收盘价缺失或非正数，无法定价：不产生交易也不记快照
                updateProgress(taskId, BacktestProgress.STAGE_RUN, i - preSize + 1, totalDays, null);
                continue;
            }

            if (acc.shares == 0) {
                if (matchGroup(req.getBuyRule(), i, ind)) {
                    Fill f = resolveFill(all, i, true, limitTh, req);
                    if (f != null) {
                        if (f.index == i) {
                            buy(acc, f.date, f.price, fee, trades);
                        } else {
                            pending = new Pending(true, f);
                        }
                    }
                }
            } else if (matchGroup(req.getSellRule(), i, ind)) {
                Fill f = resolveFill(all, i, false, limitTh, req);
                if (f != null) {
                    if (f.index == i) {
                        sell(acc, f.date, f.price, fee, trades);
                    } else {
                        pending = new Pending(false, f);
                    }
                }
            }

            dailies.add(daily(acc, k.getTradeDate(), close, init));
            updateProgress(taskId, BacktestProgress.STAGE_RUN, i - preSize + 1, totalDays, null);
        }

        if (dailies.isEmpty()) {
            throw new IllegalStateException("回测区间内没有可用的收盘价数据");
        }
        BacktestDaily last = dailies.get(dailies.size() - 1);

        // 落库（无数据库模式这三步都是空转，结果照样返回）
        Backtest bt = new Backtest();
        bt.setStockCode(code);
        bt.setStockName(stockName);
        bt.setMarket(market);
        bt.setStartDate(start);
        bt.setEndDate(end);
        bt.setInitAmount(init);
        bt.setFeeRate(fee);
        bt.setBuyRule(toJson(req.getBuyRule()));
        bt.setSellRule(toJson(req.getSellRule()));
        bt.setFinalAmount(last.getTotalAmount());
        bt.setTotalProfit(last.getProfitAmount());
        bt.setProfitRatio(last.getProfitRatio());
        bt.setTradeCount(trades.size());
        bt.setTradeDays(dailies.size());
        long id = backtestRepository.insert(bt);
        bt.setId(id);
        backtestRepository.saveDailies(id, dailies);
        backtestRepository.saveTrades(id, trades);

        BacktestResult result = new BacktestResult();
        result.setBacktest(bt);
        result.setDailies(dailies);
        result.setTrades(trades);
        result.setKlines(all.subList(Math.min(preSize, all.size()), all.size()));
        updateProgress(taskId, BacktestProgress.STAGE_DONE, totalDays, totalDays, "回测完成");
        return result;
    }

    /**
     * 代码 → 名称：结果里的「标的」要能直接看出是哪只票，而不是一串代码。
     *
     * <p>数据库模式查 waizao_stock_list，无数据库模式查内存里的 stock-list.json；
     * 查不到就返回 null，页面只显示代码。
     */
    private String resolveName(String code) {
        List<StockDirectory.Item> hit = directory.search(code, 1);
        if (hit == null || hit.isEmpty()) {
            return null;
        }
        StockDirectory.Item first = hit.get(0);
        return code.equals(first.getCode()) ? first.getName() : null;
    }

    /**
     * 无数据库模式下的跨度上限。
     *
     * <p>数据每次都要向供应商现取，跨度越大一次返回越多、越慢；这里卡在发请求之前，
     * 免得填了个十年区间把请求发出去才发现要等半天。
     */
    private void checkSpan(String start, String end) {
        if (backtestRepository.persistent()) {
            return;
        }
        long span = ChronoUnit.DAYS.between(LocalDate.parse(start, DF), LocalDate.parse(end, DF)) + 1;
        int max = props.getMaxSpanDays();
        if (span > max) {
            throw new IllegalArgumentException(
                    "无数据库模式单次回测最多 " + max + " 个自然日，当前 " + span + " 天，请缩短区间");
        }
    }

    // ============ 进度 ============

    /** 查询进度；任务不存在（已清理）返回 null，前端据此停止轮询 */
    public BacktestProgress progress(String taskId) {
        if (taskId == null) {
            return null;
        }
        sweepProgress();
        return progressMap.get(taskId);
    }

    /** 回测失败时回写进度，避免前端一直卡在「计算中」 */
    public void markError(String taskId, String message) {
        if (taskId != null) {
            progressMap.put(taskId,
                    new BacktestProgress(BacktestProgress.STAGE_ERROR, 0, 0, message));
        }
    }

    private void updateProgress(String taskId, String stage, int done, int total, String message) {
        if (taskId == null) {
            return;
        }
        progressMap.put(taskId, new BacktestProgress(stage, done, total, message));
    }

    /** 清掉 10 分钟前就已结束的进度，避免长时间运行后越堆越多 */
    private void sweepProgress() {
        long expire = System.currentTimeMillis() - 10 * 60 * 1000L;
        progressMap.entrySet().removeIf(e -> {
            BacktestProgress p = e.getValue();
            boolean ended = BacktestProgress.STAGE_DONE.equals(p.getStage())
                    || BacktestProgress.STAGE_ERROR.equals(p.getStage());
            return ended && p.getUpdateTime() < expire;
        });
    }

    // ============ 涨跌停 ============

    /**
     * 涨跌停判断阈值（各留 0.5% 容错）：
     * ST 5% → 4.5%；创业板 30x / 科创板 688 是 20% → 19.5%；其余主板 10% → 9.5%。
     */
    private BigDecimal limitThreshold(String code, String name) {
        if (name != null && name.toUpperCase().contains("ST")) {
            return new BigDecimal("0.045");
        }
        String c = code == null ? "" : code.trim();
        if (c.startsWith("688") || c.startsWith("30")) {
            return new BigDecimal("0.195");
        }
        return new BigDecimal("0.095");
    }

    /** 涨停：收盘价等于最高价，且相对昨收涨幅超过阈值 */
    private boolean isLimitUp(StockKlineDaily k, BigDecimal th) {
        BigDecimal c = k.getClosePrice();
        BigDecimal h = k.getHighPrice();
        BigDecimal pre = k.getPreClose();
        if (c == null || h == null || pre == null || pre.signum() <= 0 || c.compareTo(h) != 0) {
            return false;
        }
        return c.subtract(pre).divide(pre, SCALE_RATE, RoundingMode.HALF_UP).compareTo(th) > 0;
    }

    /** 跌停：收盘价等于最低价，且相对昨收跌幅超过阈值 */
    private boolean isLimitDown(StockKlineDaily k, BigDecimal th) {
        BigDecimal c = k.getClosePrice();
        BigDecimal l = k.getLowPrice();
        BigDecimal pre = k.getPreClose();
        if (c == null || l == null || pre == null || pre.signum() <= 0 || c.compareTo(l) != 0) {
            return false;
        }
        return c.subtract(pre).divide(pre, SCALE_RATE, RoundingMode.HALF_UP).compareTo(th.negate()) < 0;
    }

    /** 开盘涨停：开盘价等于最高价，且相对昨收涨幅超过阈值 */
    private boolean isOpenLimitUp(StockKlineDaily k, BigDecimal th) {
        BigDecimal o = k.getOpenPrice();
        BigDecimal h = k.getHighPrice();
        BigDecimal pre = k.getPreClose();
        if (o == null || h == null || pre == null || pre.signum() <= 0 || o.compareTo(h) != 0) {
            return false;
        }
        return o.subtract(pre).divide(pre, SCALE_RATE, RoundingMode.HALF_UP).compareTo(th) > 0;
    }

    /** 开盘跌停：开盘价等于最低价，且相对昨收跌幅超过阈值 */
    private boolean isOpenLimitDown(StockKlineDaily k, BigDecimal th) {
        BigDecimal o = k.getOpenPrice();
        BigDecimal l = k.getLowPrice();
        BigDecimal pre = k.getPreClose();
        if (o == null || l == null || pre == null || pre.signum() <= 0 || o.compareTo(l) != 0) {
            return false;
        }
        return o.subtract(pre).divide(pre, SCALE_RATE, RoundingMode.HALF_UP).compareTo(th.negate()) < 0;
    }

    /**
     * 决定一次信号的成交日与成交价。
     *
     * @param i     信号日在 all 中的下标
     * @param isBuy 买入 / 卖出
     * @return 成交方案；无法成交返回 null（该信号作废，不再往后尝试）
     */
    private Fill resolveFill(List<StockKlineDaily> all, int i, boolean isBuy,
                             BigDecimal th, BacktestRunRequest req) {
        StockKlineDaily t = all.get(i);
        BigDecimal close = t.getClosePrice();
        BacktestRunRequest.LimitConfig limit = limitCfg(req, isBuy);
        int strategy = limit.getStrategy();

        // T 日是否受阻：买入遇涨停（没人卖）、卖出遇跌停（没人买）
        boolean blocked = isBuy ? isLimitUp(t, th) : isLimitDown(t, th);
        if (!blocked || strategy == STRATEGY_IGNORE) {
            return new Fill(i, t.getTradeDate(), close);
        }

        // 策略 2 / 3：推到次日开盘
        if (i + 1 >= all.size()) {
            return null;
        }
        StockKlineDaily n = all.get(i + 1);
        BigDecimal open = n.getOpenPrice();
        if (open == null || open.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        // 次日开盘仍受阻（买入涨停开盘 / 卖出跌停开盘）→ 作废
        if (isBuy ? isOpenLimitUp(n, th) : isOpenLimitDown(n, th)) {
            return null;
        }
        if (strategy == STRATEGY_NEXT_COND && hasConds(limit)) {
            // 次日开盘相对信号日 T 收盘的带符号涨跌幅（涨幅为正、跌幅为负）
            BigDecimal chg = open.subtract(close).divide(close, SCALE_RATE, RoundingMode.HALF_UP);
            // 满足任一（或）条件即成交，否则作废
            boolean ok = matchAnyLimitCond(limit, chg);
            return ok ? new Fill(i + 1, n.getTradeDate(), open) : null;
        }
        // 策略 2（或策略 3 未配条件）：按次日开盘价成交
        return new Fill(i + 1, n.getTradeDate(), open);
    }

    /** 策略 3 是否配了阈值条件 */
    private boolean hasConds(BacktestRunRequest.LimitConfig limit) {
        return limit.getConds() != null && !limit.getConds().isEmpty();
    }

    /**
     * 判断次日开盘的带符号涨跌幅 chg（涨幅为正、跌幅为负）是否命中任一条阈值条件。
     *
     * <p>每条条件 = 方向(dir) + 比较符(op) + 阈值(ratio)。方向决定走哪一轴并算出「该方向的幅度」：
     * <ul>
     *   <li>dir=up（涨幅）：当天需真的上涨（chg&gt;0），幅度 = chg</li>
     *   <li>dir=down（跌幅）：当天需真的下跌（chg&lt;0），幅度 = -chg</li>
     * </ul>
     * 方向不符（该行不成立）则本行直接不命中；幅度再按 gt/lt 与 ratio 比较。
     */
    private boolean matchAnyLimitCond(BacktestRunRequest.LimitConfig limit, BigDecimal chg) {
        for (BacktestRunRequest.LimitConfig.LimitCond c : limit.getConds()) {
            if (c == null || c.getRatio() == null || c.getRatio().signum() < 0) {
                continue;
            }
            BigDecimal mag = "down".equals(c.getDir()) ? chg.negate() : chg;
            // 方向不符（如选涨幅当日却是下跌），本行不成立
            if (mag.signum() < 0) {
                continue;
            }
            boolean hit = "lt".equals(c.getOp())
                    ? mag.compareTo(c.getRatio()) < 0
                    : mag.compareTo(c.getRatio()) > 0;
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** 取某一侧的涨跌停处理配置：请求里没带（或为 null）时按「无视」处理 */
    private BacktestRunRequest.LimitConfig limitCfg(BacktestRunRequest req, boolean isBuy) {
        BacktestRunRequest.LimitConfig c = isBuy ? req.getBuyLimit() : req.getSellLimit();
        return c != null ? c : new BacktestRunRequest.LimitConfig();
    }

    // ============ 成交与快照 ============

    private void buy(Account acc, String date, BigDecimal price, BigDecimal fee,
                     List<BacktestTrade> trades) {
        // 全仓买入：单价按含摩擦成本计算，股数向下取整到 100 股
        BigDecimal unitCost = price.multiply(BigDecimal.ONE.add(fee));
        long maxLong = acc.cash.divide(unitCost, 0, RoundingMode.FLOOR).longValue();
        if (maxLong > Integer.MAX_VALUE) {
            maxLong = Integer.MAX_VALUE;
        }
        int lots = (int) ((maxLong / LOT) * LOT);
        if (lots < LOT) {
            return;
        }
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(lots));
        BigDecimal feeAmt = turnover.multiply(fee).setScale(SCALE_MONEY, RoundingMode.HALF_UP);
        acc.cash = acc.cash.subtract(turnover).subtract(feeAmt);
        acc.shares = lots;
        trades.add(newTrade(date, BacktestTrade.ACTION_BUY, lots, price, turnover, feeAmt, acc.cash));
    }

    private void sell(Account acc, String date, BigDecimal price, BigDecimal fee,
                      List<BacktestTrade> trades) {
        BigDecimal turnover = price.multiply(BigDecimal.valueOf(acc.shares));
        BigDecimal feeAmt = turnover.multiply(fee).setScale(SCALE_MONEY, RoundingMode.HALF_UP);
        acc.cash = acc.cash.add(turnover).subtract(feeAmt);
        trades.add(newTrade(date, BacktestTrade.ACTION_SELL, acc.shares, price, turnover,
                feeAmt, acc.cash));
        acc.shares = 0;
    }

    private BacktestDaily daily(Account acc, String date, BigDecimal close, BigDecimal init) {
        BigDecimal posValue = close.multiply(BigDecimal.valueOf(acc.shares))
                .setScale(SCALE_MONEY, RoundingMode.HALF_UP);
        BigDecimal total = acc.cash.add(posValue);
        BigDecimal profit = total.subtract(init);
        BacktestDaily d = new BacktestDaily();
        d.setTradeDate(date);
        d.setClosePrice(close);
        d.setCash(acc.cash.setScale(SCALE_MONEY, RoundingMode.HALF_UP));
        d.setPositionShares(acc.shares);
        d.setPositionValue(posValue);
        d.setTotalAmount(total.setScale(SCALE_MONEY, RoundingMode.HALF_UP));
        d.setProfitAmount(profit.setScale(SCALE_MONEY, RoundingMode.HALF_UP));
        d.setProfitRatio(profit.divide(init, SCALE_RATE, RoundingMode.HALF_UP));
        return d;
    }

    /** 回测过程中的账户状态：cash / shares 在买卖里直接改，所以用可变对象持有 */
    private static class Account {
        private BigDecimal cash;
        private int shares;

        Account(BigDecimal cash) {
            this.cash = cash;
        }
    }

    /** 一次信号最终确定的成交方案 */
    private static class Fill {
        /** 成交日在 all 中的下标 */
        private final int index;
        private final String date;
        private final BigDecimal price;

        Fill(int index, String date, BigDecimal price) {
            this.index = index;
            this.date = date;
            this.price = price;
        }
    }

    /** T 日成交不了、预约到次日执行的订单 */
    private static class Pending {
        private final boolean buy;
        private final Fill fill;

        Pending(boolean buy, Fill fill) {
            this.buy = buy;
            this.fill = fill;
        }
    }

    // ============ 查询 ============

    /** 历史回测列表（无数据库模式恒为空） */
    public List<Backtest> list(int limit) {
        return backtestRepository.list(limit);
    }

    /** 回看某次回测（汇总 + 每日快照 + 交易明细 + 区间K线） */
    public BacktestResult detail(long id) {
        return backtestRepository.detail(id);
    }

    /** 删除一次回测（连带快照与明细） */
    public void delete(long id) {
        backtestRepository.delete(id);
    }

    // ============ 数据校验与取数 ============

    /**
     * 起止两天的数据必须齐全：缺哪天就让数据源补哪天，补完再判一次，仍缺才报错。
     *
     * <p>无数据库模式不走这里 —— 数据现取，取不到就是供应商没有，补也没用。
     */
    private void ensureBoundaryData(String taskId, String code, String market, String start, String end) {
        List<String> miss = klineRepository.missing(market, code, start, end);
        if (miss.isEmpty()) {
            return;
        }
        updateProgress(taskId, BacktestProgress.STAGE_FETCH, 0, miss.size(),
                "正在从供应商同步缺失的日K数据: " + String.join("、", miss));
        int done = 0;
        for (String d : miss) {
            klineRepository.prepareOne(market, code, d);
            done++;
            updateProgress(taskId, BacktestProgress.STAGE_FETCH, done, miss.size(),
                    "已同步 " + d + "（" + done + "/" + miss.size() + "）");
        }
        // 补完再判一次：仍缺说明供应商那边也没有（非交易日 / 停牌 / 超出数据范围）
        List<String> still = klineRepository.missing(market, code, start, end);
        if (!still.isEmpty()) {
            throw new IllegalStateException("已从供应商同步，但仍缺少 " + String.join("、", still)
                    + " 的日K数据（可能是非交易日或供应商无该日数据），无法回测");
        }
    }

    /** 回测区间之前需要多取多少个交易日用于指标预热 */
    private int warmupDays(BacktestRule buy, BacktestRule sell) {
        int max = 0;
        for (BacktestCondition c : allConditions(buy, sell)) {
            if (c.getN() != null) {
                max = Math.max(max, c.getN());
            }
            if (BacktestCondition.TYPE_ABOVE_MA_DAYS.equals(c.getType()) && c.getM() != null) {
                max = Math.max(max, c.getM());
            }
        }
        // 多留几天余量，避免边界差一天导致首个信号日指标未就绪
        return max + 5;
    }

    private List<BacktestCondition> allConditions(BacktestRule buy, BacktestRule sell) {
        List<BacktestCondition> all = new ArrayList<>();
        if (buy != null && buy.getConditions() != null) {
            all.addAll(buy.getConditions());
        }
        if (sell != null && sell.getConditions() != null) {
            all.addAll(sell.getConditions());
        }
        return all;
    }

    // ============ 信号判断 ============

    /** 策略组是否命中：or 任一组件满足即命中，and 全部组件满足才命中 */
    private boolean matchGroup(BacktestRule rule, int i, Indicator ind) {
        if (rule == null || rule.isEmpty()) {
            return false;
        }
        if (BacktestRule.LOGIC_OR.equals(rule.getLogic())) {
            for (BacktestCondition c : rule.getConditions()) {
                if (match(c, i, ind)) {
                    return true;
                }
            }
            return false;
        }
        for (BacktestCondition c : rule.getConditions()) {
            if (!match(c, i, ind)) {
                return false;
            }
        }
        return true;
    }

    /** 单个判断组件是否命中；指标未就绪一律视为不命中 */
    private boolean match(BacktestCondition c, int i, Indicator ind) {
        boolean gt = BacktestCondition.OP_GT.equals(c.getOp());
        switch (c.getType() == null ? "" : c.getType()) {
            case BacktestCondition.TYPE_CHANGE: {
                BigDecimal chg = ind.change(i, c.getN());
                if (chg == null || c.getX() == null) {
                    return false;
                }
                int cmp = chg.compareTo(c.getX());
                return gt ? cmp > 0 : cmp < 0;
            }
            case BacktestCondition.TYPE_ABOVE_MA_DAYS:
                return ind.continues(i, c.getN(), c.getM(), gt);
            case BacktestCondition.TYPE_CLOSE_MA: {
                BigDecimal ma = ind.ma(i, c.getN());
                BigDecimal close = ind.close(i);
                if (ma == null || close == null) {
                    return false;
                }
                int cmp = close.compareTo(ma);
                return gt ? cmp > 0 : cmp < 0;
            }
            case BacktestCondition.TYPE_VOLUME_MA: {
                BigDecimal avg = ind.avgVolume(i, c.getN());
                BigDecimal vol = ind.volume(i);
                if (avg == null || vol == null || c.getRatio() == null) {
                    return false;
                }
                int cmp = vol.compareTo(avg.multiply(c.getRatio()));
                return gt ? cmp > 0 : cmp < 0;
            }
            default:
                return false;
        }
    }

    private BacktestTrade newTrade(String date, String action, int shares, BigDecimal price,
                                   BigDecimal turnover, BigDecimal fee, BigDecimal cashAfter) {
        BacktestTrade t = new BacktestTrade();
        t.setTradeDate(date);
        t.setAction(action);
        t.setShares(shares);
        t.setPrice(price);
        t.setTurnover(turnover.setScale(SCALE_MONEY, RoundingMode.HALF_UP));
        t.setFee(fee);
        t.setCashAfter(cashAfter.setScale(SCALE_MONEY, RoundingMode.HALF_UP));
        return t;
    }

    private String toJson(BacktestRule rule) {
        try {
            return mapper.writeValueAsString(rule);
        } catch (Exception e) {
            // 策略序列化失败不阻断回测，落个空对象保证记录可查
            return "{\"logic\":\"and\",\"conditions\":[]}";
        }
    }

    // ============ 指标（滑动窗口） ============

    /** 基于收盘价序列的滑动窗口指标计算器 */
    static class Indicator {

        private final List<BigDecimal> closes;
        /** 与 closes 同序的成交量，缺失为 null */
        private final List<BigDecimal> volumes;
        /** 均线周期 → 每日均线值（数据不足的位置为 null） */
        private final Map<Integer, BigDecimal[]> maCache = new HashMap<>();

        Indicator(List<BigDecimal> closes, List<BigDecimal> volumes) {
            this.closes = closes;
            this.volumes = volumes;
        }

        BigDecimal close(int i) {
            return i >= 0 && i < closes.size() ? closes.get(i) : null;
        }

        /** 当日成交量 */
        BigDecimal volume(int i) {
            return i >= 0 && i < volumes.size() ? volumes.get(i) : null;
        }

        /**
         * 前 n 日（不含当日）平均成交量；数据不足或窗口内有缺失返回 null。
         *
         * <p>不含当日是刻意的：要比的是「今天的量 vs 之前 n 天的平均水平」，
         * 把当天算进去会稀释掉放量本身。
         */
        BigDecimal avgVolume(int i, int n) {
            if (n <= 0 || i < n || i >= volumes.size()) {
                return null;
            }
            BigDecimal sum = BigDecimal.ZERO;
            for (int j = i - n; j <= i - 1; j++) {
                BigDecimal v = volumes.get(j);
                if (v == null) {
                    return null;
                }
                sum = sum.add(v);
            }
            return sum.divide(BigDecimal.valueOf(n), SCALE_RATE, RoundingMode.HALF_UP);
        }

        /** 第 i 日的 k 日均线（含当日），数据不足返回 null */
        BigDecimal ma(int i, int k) {
            if (k <= 0 || i < 0 || i >= closes.size()) {
                return null;
            }
            BigDecimal[] arr = maCache.computeIfAbsent(k, this::calcMa);
            return arr[i];
        }

        /**
         * 滑动窗口算 k 日均线。
         *
         * <p>窗口内只要有一天收盘价缺失，该日均线就置空 —— 宁可不给信号，也不能用
         * 不足 k 天的数据算出一个"看起来对"的均线，那会直接导致错误买卖。
         */
        private BigDecimal[] calcMa(int k) {
            int n = closes.size();
            BigDecimal[] arr = new BigDecimal[n];
            BigDecimal sum = BigDecimal.ZERO;
            int valid = 0;
            Deque<BigDecimal> window = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                BigDecimal c = closes.get(i);
                window.addLast(c);
                if (c != null) {
                    sum = sum.add(c);
                    valid++;
                }
                if (window.size() > k) {
                    BigDecimal out = window.removeFirst();
                    if (out != null) {
                        sum = sum.subtract(out);
                        valid--;
                    }
                }
                if (window.size() == k && valid == k) {
                    arr[i] = sum.divide(BigDecimal.valueOf(k), SCALE_RATE, RoundingMode.HALF_UP);
                }
            }
            return arr;
        }

        /** 近 n 日涨幅：(close[i] - close[i-n]) / close[i-n]，数据不足返回 null */
        BigDecimal change(int i, int n) {
            if (n <= 0 || i < n || i >= closes.size()) {
                return null;
            }
            BigDecimal now = closes.get(i);
            BigDecimal prev = closes.get(i - n);
            if (now == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) {
                return null;
            }
            return now.subtract(prev).divide(prev, SCALE_RATE, RoundingMode.HALF_UP);
        }

        /** 连续 n 日收盘价都站上(gt=true)/跌破(gt=false) m 日线 */
        boolean continues(int i, int n, int m, boolean gt) {
            if (n <= 0 || m <= 0 || i < n - 1 || i >= closes.size()) {
                return false;
            }
            for (int j = i - n + 1; j <= i; j++) {
                BigDecimal ma = ma(j, m);
                BigDecimal c = closes.get(j);
                if (ma == null || c == null) {
                    return false;
                }
                int cmp = c.compareTo(ma);
                if (gt ? cmp <= 0 : cmp >= 0) {
                    return false;
                }
            }
            return true;
        }
    }
}
