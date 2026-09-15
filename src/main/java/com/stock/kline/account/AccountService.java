package com.stock.kline.account;

import com.stock.kline.support.StockDirectory;
import com.stock.kline.watch.Quote;
import com.stock.kline.watch.SinaQuoteClient;
import com.stock.kline.watch.WatchConfigStore;
import com.stock.kline.watch.WatchSettings;
import com.stock.kline.watch.WatchTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 我的账户：账户设置 / 持仓 / 操作记录的业务核心（需求 v1.5.1 第 ③ 条）。
 *
 * <p>三块职责：
 * <ol>
 *   <li><b>账户与持仓</b>：走 {@link AccountRepository}（Db / File 双实现），落盘；</li>
 *   <li><b>收益计算</b>：现价与昨收用新浪实时行情现取（与盯盘同一条链路），
 *       算出持仓当日收益、总收益、账户当前总市值与总收益 —— 不落盘；</li>
 *   <li><b>操作记录</b>：买入 / 卖出实时生效、不落盘（只留内存，见 {@link TradeRecord}），
 *       买入把标的加入盯盘、卖出则移除，并按成本价与数量增减可用金额。</li>
 * </ol>
 *
 * <p>持仓现价复用新浪行情而不是本地日K：账户页要的是"此刻的浮动盈亏"，
 * 日K最近收盘价在盘中会明显滞后。行情拿不到时回落到成本价，页面据此提示兜底。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 单账户最多持仓条数：页面一次查一轮行情，太多既慢也容易被行情接口限流 */
    private static final int MAX_POSITIONS = 200;
    /** 每个用户保留的操作记录条数上限（内存数据，不落盘，超出丢最旧的） */
    private static final int MAX_RECORDS = 200;

    /** 金额口径：一律四位小数存储，展示时前端保留两位 */
    private static final int MONEY_SCALE = 4;
    /** 比率口径：百分比保留两位小数 */
    private static final int PCT_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final AccountRepository repository;
    private final ObjectProvider<SinaQuoteClient> quoteClientProvider;
    private final ObjectProvider<WatchConfigStore> watchStoreProvider;
    private final ObjectProvider<StockDirectory> directoryProvider;

    /** userId → 该用户的操作记录（新的在前）；<b>刻意不落盘</b> */
    private final ConcurrentMap<Long, List<TradeRecord>> records = new ConcurrentHashMap<>();
    private final AtomicLong recordSeq = new AtomicLong(0);

    public AccountService(AccountRepository repository,
                          ObjectProvider<SinaQuoteClient> quoteClientProvider,
                          ObjectProvider<WatchConfigStore> watchStoreProvider,
                          ObjectProvider<StockDirectory> directoryProvider) {
        this.repository = repository;
        this.quoteClientProvider = quoteClientProvider;
        this.watchStoreProvider = watchStoreProvider;
        this.directoryProvider = directoryProvider;
    }

    /* ==================== 账户 ==================== */

    /** 账户设置（期初市值 / 可用金额）；从没存过时给一份空账户，页面照常可编辑 */
    public Account getAccount(long userId) {
        Account a = repository.findAccount(userId);
        return a == null ? newAccount(userId) : a;
    }

    /**
     * 保存账户设置。
     *
     * <p>可用金额允许为负（融资 / 手工记账场景），但页面会提示；
     * 期初市值 < 0 属于明显误填，直接拒绝。
     */
    public Account saveAccount(long userId, Account incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        BigDecimal init = nz(incoming.getInitValue());
        BigDecimal cash = nz(incoming.getCash());
        if (init.signum() < 0) {
            throw new IllegalArgumentException("账户期初市值不能为负数");
        }
        if (init.compareTo(BigDecimal.valueOf(1_000_000_000_000L)) > 0) {
            throw new IllegalArgumentException("账户期初市值超过 1 万亿，请确认是否填错");
        }
        Account a = repository.findAccount(userId);
        String now = LocalDateTime.now().format(DF);
        if (a == null) {
            a = newAccount(userId);
            a.setCreateTime(now);
        }
        a.setInitValue(init.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        a.setCash(cash.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        a.setRemark(incoming.getRemark());
        a.setUpdateTime(now);
        repository.saveAccount(a);
        return a;
    }

    /** 期初市值与可用金额一起重设（「重置账户」用，持仓不动） */
    public Account resetAccount(long userId, BigDecimal initValue, BigDecimal cash) {
        Account incoming = newAccount(userId);
        incoming.setInitValue(initValue);
        incoming.setCash(cash);
        return saveAccount(userId, incoming);
    }

    /* ==================== 持仓 ==================== */

    public List<Position> listPositions(long userId) {
        return repository.listPositions(userId);
    }

    /**
     * 新增或更新一条持仓。
     *
     * <p>页面上「同一只标的只能有一条持仓」是硬约束：重复录入会让成本价与数量对不上账。
     * 所以这里先按代码去重 —— 提交的代码已存在且不是同一条时，直接报错让用户改数量。
     */
    public Position savePosition(long userId, Position incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("持仓内容不能为空");
        }
        String code = normalizeCode(incoming.getCode());
        if (code == null) {
            throw new IllegalArgumentException("请选择持仓标的");
        }
        BigDecimal cost = nz(incoming.getCostPrice());
        if (cost.signum() <= 0) {
            throw new IllegalArgumentException("成本价必须大于 0");
        }
        int qty = incoming.getQuantity();
        if (qty <= 0) {
            throw new IllegalArgumentException("持仓数量必须大于 0");
        }
        for (Position exist : repository.listPositions(userId)) {
            boolean sameRow = incoming.getId() != null && incoming.getId().equals(exist.getId());
            if (!sameRow && code.equals(exist.getCode())) {
                throw new IllegalArgumentException(exist.getName() + "(" + code + ") 已有持仓，请直接修改数量");
            }
        }
        int count = repository.listPositions(userId).size();
        if (incoming.getId() == null && count >= MAX_POSITIONS) {
            throw new IllegalArgumentException("持仓最多 " + MAX_POSITIONS + " 条");
        }
        String now = LocalDateTime.now().format(DF);
        Position p = new Position();
        p.setId(incoming.getId());
        p.setUserId(userId);
        p.setCode(code);
        p.setName(nameOf(code, incoming.getName()));
        p.setCostPrice(cost.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        p.setQuantity(qty);
        p.setCreateTime(now);
        p.setUpdateTime(now);
        return repository.savePosition(p);
    }

    /** 删除一条持仓 */
    public void deletePosition(long userId, long id) {
        repository.deletePosition(userId, id);
    }

    /** 清空持仓（账户设置保留） */
    public void clearPositions(long userId) {
        repository.deleteAllPositions(userId);
    }

    /* ==================== 操作记录（实时生效、不落盘） ==================== */

    /**
     * 记一笔买 / 卖，并<b>立即</b>作用到持仓、可用金额与盯盘名单上。
     *
     * <p><b>价格口径</b>：
     * 已有持仓时一律取该标的的<b>持仓成本价</b>（卖出也按成本价回款），
     * 这样「买 100 股 → 卖 100 股」能精确回到原余额，不会因为盘中价格跳动而对不上账。
     * 买入一只<b>尚无持仓</b>的标的时，价格由 {@code price} 指定 —— 页面会预填该标的的
     * 实时行情价（取不到则留空由用户手填），用户也可以改成自己的实际成交价。
     *
     * <p><b>买入 = 建仓 + 加盯盘</b>：这正是需求里「买入则加入盯盘」的落点 ——
     * 标的进盯盘名单的前提就是用户买了它，所以买入允许新建持仓（{@code price} 必填）；
     * 卖出则要求持仓已存在且数量足够，清仓（卖出后剩 0）时把标的移出盯盘。
     */
    public TradeRecord addRecord(long userId, String action, String code, String name,
                                 Integer quantity, BigDecimal price) {
        String act = TradeRecord.ACTION_BUY.equalsIgnoreCase(action) ? TradeRecord.ACTION_BUY
                : TradeRecord.ACTION_SELL.equalsIgnoreCase(action) ? TradeRecord.ACTION_SELL : null;
        if (act == null) {
            throw new IllegalArgumentException("操作类型只能是买入或卖出");
        }
        String c = normalizeCode(code);
        if (c == null) {
            throw new IllegalArgumentException("请选择操作标的");
        }
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("数量必须大于 0");
        }
        int qty = quantity;

        List<Position> positions = repository.listPositions(userId);
        Position hit = null;
        for (Position p : positions) {
            if (c.equals(p.getCode())) {
                hit = p;
                break;
            }
        }

        boolean buy = TradeRecord.ACTION_BUY.equals(act);
        // 名称：优先用页面选中的那个（用户看到的就是它），缺了才去名录精确查
        String displayName = nameOf(c, name);
        BigDecimal unit;
        if (hit != null) {
            // 已有持仓：一律按成本价记账，保证买卖可精确回退
            unit = nz(hit.getCostPrice());
        } else if (buy) {
            // 买入新标的 = 建仓：用传入价（页面预填实时行情价）作为成本价
            BigDecimal given = nz(price);
            if (given.signum() <= 0) {
                throw new IllegalArgumentException("买入 " + displayName + " 前请填写成交价");
            }
            if (positions.size() >= MAX_POSITIONS) {
                throw new IllegalArgumentException("持仓最多 " + MAX_POSITIONS + " 条");
            }
            unit = given;
        } else {
            throw new IllegalArgumentException("还没有 " + c + " 的持仓，无法卖出");
        }
        unit = unit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal turnover = unit.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        Account acct = getAccount(userId);
        BigDecimal cash = nz(acct.getCash());

        Position pos;
        if (buy) {
            if (hit == null) {
                // 新建持仓：成本价 = 本次成交价，数量 = 本次数量
                Position fresh = new Position();
                fresh.setUserId(userId);
                fresh.setCode(c);
                fresh.setName(displayName);
                fresh.setCostPrice(unit);
                fresh.setQuantity(qty);
                String now = LocalDateTime.now().format(DF);
                fresh.setCreateTime(now);
                fresh.setUpdateTime(now);
                pos = repository.savePosition(fresh);
            } else {
                if (hit.getQuantity() < 1) {
                    throw new IllegalArgumentException("持仓数量异常，请重新配置持仓");
                }
                hit.setQuantity(hit.getQuantity() + qty);
                hit.setUpdateTime(LocalDateTime.now().format(DF));
                pos = repository.savePosition(hit);
            }
            // 买入：按成本价与数量扣减可用金额
            cash = cash.subtract(turnover);
            watchAdd(userId, pos);
        } else {
            if (hit.getQuantity() < qty) {
                throw new IllegalArgumentException("持仓只有 " + hit.getQuantity() + " 股，卖出 " + qty + " 股超出持仓");
            }
            int left = hit.getQuantity() - qty;
            if (left == 0) {
                repository.deletePosition(userId, hit.getId());
                watchRemove(userId, hit.getCode());
            } else {
                hit.setQuantity(left);
                hit.setUpdateTime(LocalDateTime.now().format(DF));
                repository.savePosition(hit);
            }
            pos = hit;
            // 卖出：按成本价与数量加回可用金额
            cash = cash.add(turnover);
        }
        saveAccountCash(userId, acct, cash);

        TradeRecord r = new TradeRecord();
        r.setId(recordSeq.incrementAndGet());
        r.setUserId(userId);
        r.setAction(act);
        r.setCode(c);
        r.setName(pos.getName());
        r.setPrice(unit);
        r.setQuantity(qty);
        r.setTurnover(turnover);
        r.setCashAfter(cash);
        r.setTime(LocalDateTime.now().format(DF));
        pushRecord(userId, r);

        log.info("[account] 用户 {} {} {} {} 股，价格 {}，成交额 {}，可用金额 {}",
                userId, buy ? "买入" : "卖出",
                r.getName(), qty, r.getPrice(), turnover, cash);
        return r;
    }

    /** 某个用户的操作记录（新的在前，不落盘、进程重启即清空） */
    public List<TradeRecord> listRecords(long userId) {
        List<TradeRecord> list = records.get(userId);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /**
     * 给「记一笔操作」表单预填价格：已有持仓返回其成本价，否则取该标的实时行情价。
     *
     * <p>取不到行情时价格返回 null，页面留空让用户自己填实际成交价。
     */
    public BigDecimal suggestPrice(long userId, String code) {
        String c = normalizeCode(code);
        if (c == null) {
            return null;
        }
        for (Position p : repository.listPositions(userId)) {
            if (c.equals(p.getCode())) {
                return nz(p.getCostPrice());
            }
        }
        SinaQuoteClient client = quoteClientProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            Quote q = client.fetch(new WatchTarget(c, c));
            return q == null ? null : q.getPrice();
        } catch (Exception e) {
            log.warn("[account] 预填价格取行情失败 {}: {}", c, e.getMessage());
            return null;
        }
    }

    /** 清空操作记录（不影响持仓与余额） */
    public void clearRecords(long userId) {
        records.remove(userId);
    }

    /* ==================== 汇总（收益计算） ==================== */

    /**
     * 账户汇总：账户设置 + 持仓明细（含现价、当日/总收益）+ 账户级收益。
     *
     * <p>行情在本方法内并发批量取一次，取不到的持仓按成本价兜底并标记 {@code quoted=false}，
     * 这样页面既能看到数字（不空），也知道哪个是兜底值。
     */
    public AccountSummary summary(long userId) {
        Account acct = getAccount(userId);
        List<Position> positions = repository.listPositions(userId);
        Map<String, Quote> quotes = fetchQuotes(positions);

        AccountSummary s = new AccountSummary();
        s.setInitValue(nz(acct.getInitValue()));
        s.setCash(nz(acct.getCash()));
        s.setRemark(acct.getRemark());

        BigDecimal positionValue = BigDecimal.ZERO;
        BigDecimal positionCost = BigDecimal.ZERO;
        BigDecimal positionProfit = BigDecimal.ZERO;
        BigDecimal dayProfit = BigDecimal.ZERO;
        BigDecimal preCloseValue = BigDecimal.ZERO;
        boolean dayProfitKnown = true;

        List<AccountSummary.PositionView> views = new ArrayList<>(positions.size());
        for (Position p : positions) {
            Quote q = quotes.get(p.getCode());
            AccountSummary.PositionView v = new AccountSummary.PositionView();
            v.setId(p.getId());
            v.setCode(p.getCode());
            v.setName(p.getName());
            v.setCostPrice(p.getCostPrice());
            v.setQuantity(p.getQuantity());

            BigDecimal cost = nz(p.getCostPrice());
            BigDecimal qty = BigDecimal.valueOf(p.getQuantity());
            BigDecimal costValue = cost.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal price = q != null && q.getPrice() != null ? q.getPrice() : cost;
            BigDecimal marketValue = price.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal profit = marketValue.subtract(costValue);

            v.setPrice(price);
            v.setCostValue(costValue);
            v.setMarketValue(marketValue);
            v.setTotalProfit(profit);
            v.setTotalProfitPercent(percent(profit, costValue));
            if (q != null) {
                v.setQuoted(true);
                v.setPreClose(q.getPreClose());
                v.setQuoteTime(q.getTimeText());
                BigDecimal preClose = q.getPreClose();
                if (preClose != null && preClose.signum() > 0) {
                    BigDecimal dayProfitOne = price.subtract(preClose).multiply(qty)
                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                    v.setDayProfit(dayProfitOne);
                    v.setDayProfitPercent(percent(price.subtract(preClose), preClose));
                    dayProfit = dayProfit.add(dayProfitOne);
                    preCloseValue = preCloseValue.add(preClose.multiply(qty));
                } else {
                    dayProfitKnown = false;
                }
            } else {
                dayProfitKnown = false;
            }

            positionValue = positionValue.add(marketValue);
            positionCost = positionCost.add(costValue);
            positionProfit = positionProfit.add(profit);
            views.add(v);
        }

        BigDecimal totalValue = positionValue.add(nz(acct.getCash()));
        BigDecimal totalProfit = totalValue.subtract(nz(acct.getInitValue()));

        s.setPositions(views);
        s.setPositionValue(positionValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        s.setPositionCost(positionCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        s.setPositionProfit(positionProfit.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        s.setTotalValue(totalValue.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        s.setTotalProfit(totalProfit.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        s.setTotalProfitPercent(percent(totalProfit, nz(acct.getInitValue())));
        s.setDayProfit(dayProfit.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        s.setDayProfitPercent(dayProfitKnown && preCloseValue.signum() > 0
                ? percent(dayProfit, preCloseValue) : null);
        return s;
    }

    /* ==================== 内部实现 ==================== */

    /** 批量取现价：有行情客户端时并发取，取不到的标的直接缺省（调用侧按成本价兜底） */
    private Map<String, Quote> fetchQuotes(List<Position> positions) {
        Map<String, Quote> out = new LinkedHashMap<>();
        SinaQuoteClient client = quoteClientProvider.getIfAvailable();
        if (client == null || positions.isEmpty()) {
            return out;
        }
        for (Position p : positions) {
            if (p.getCode() == null) {
                continue;
            }
            try {
                String name = p.getName() == null || p.getName().trim().isEmpty() ? p.getCode() : p.getName();
                Quote q = client.fetch(new WatchTarget(name, p.getCode()));
                if (q != null) {
                    out.put(p.getCode(), q);
                }
            } catch (Exception e) {
                // 单只取不到不影响其它持仓的收益展示
                log.warn("[account] 持仓行情取数失败 {}: {}", p.getCode(), e.getMessage());
            }
        }
        return out;
    }

    /** 更新可用金额（账户设置其余字段不动） */
    private void saveAccountCash(long userId, Account acct, BigDecimal cash) {
        acct.setCash(cash.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        acct.setUpdateTime(LocalDateTime.now().format(DF));
        repository.saveAccount(acct);
    }

    /** 把标的加入该用户的盯盘名单（已有则不动）；盯盘模块关掉时静默跳过 */
    private void watchAdd(long userId, Position p) {
        WatchConfigStore store = watchStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            WatchSettings s = store.snapshot(userId);
            for (WatchSettings.StockRef r : s.getStocks()) {
                if (p.getCode().equals(r.getCode())) {
                    return;
                }
            }
            s.getStocks().add(new WatchSettings.StockRef(p.getCode(), p.getName()));
            store.update(userId, s);
            log.info("[account] 用户 {} 买入 {}，已加入盯盘", userId, p.getCode());
        } catch (Exception e) {
            log.warn("[account] 加入盯盘失败 {}: {}", p.getCode(), e.getMessage());
        }
    }

    /** 把标的从该用户的盯盘名单移除（清仓时调用） */
    private void watchRemove(long userId, String code) {
        WatchConfigStore store = watchStoreProvider.getIfAvailable();
        if (store == null) {
            return;
        }
        try {
            WatchSettings s = store.snapshot(userId);
            List<WatchSettings.StockRef> kept = new ArrayList<>();
            boolean removed = false;
            for (WatchSettings.StockRef r : s.getStocks()) {
                if (code.equals(r.getCode())) {
                    removed = true;
                    continue;
                }
                kept.add(r);
            }
            if (!removed) {
                return;
            }
            s.setStocks(kept);
            store.update(userId, s);
            log.info("[account] 用户 {} 清仓 {}，已移出盯盘", userId, code);
        } catch (Exception e) {
            log.warn("[account] 移出盯盘失败 {}: {}", code, e.getMessage());
        }
    }

    private void pushRecord(long userId, TradeRecord r) {
        List<TradeRecord> fresh = new ArrayList<>();
        fresh.add(r);
        List<TradeRecord> old = records.get(userId);
        if (old != null) {
            for (TradeRecord one : old) {
                if (fresh.size() >= MAX_RECORDS) {
                    break;
                }
                fresh.add(one);
            }
        }
        records.put(userId, fresh);
    }

    private static Account newAccount(long userId) {
        Account a = new Account();
        a.setUserId(userId);
        a.setInitValue(BigDecimal.ZERO);
        a.setCash(BigDecimal.ZERO);
        return a;
    }

    /** 代码规范化：只认 6 位沪深A股，非法给 null */
    private static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        String c = code.trim();
        return c.length() == 6 && c.chars().allMatch(Character::isDigit) ? c : null;
    }

    /**
     * 名称：优先用传入的，缺了去名录补，都拿不到就退回代码。
     *
     * <p>名录查询必须走 {@link StockDirectory#search} 后再<b>按代码精确匹配</b>：
     * 名录的 search 是「包含」匹配，直接取第一条会串名 —— 例如查 000858 时
     * 排在前面的是代码里含该串的其它标的（"500信息" 之流），拿到的名字就完全不对。
     */
    private String nameOf(String code, String given) {
        if (given != null && !given.trim().isEmpty()) {
            return given.trim();
        }
        StockDirectory dir = directoryProvider.getIfAvailable();
        if (dir != null) {
            try {
                List<StockDirectory.Item> items = dir.search(code, 10);
                for (StockDirectory.Item it : items) {
                    if (code.equals(it.getCode())) {
                        return it.getName() != null ? it.getName() : code;
                    }
                }
            } catch (Exception e) {
                log.debug("[account] 名录查名失败 {}: {}", code, e.getMessage());
            }
        }
        return code;
    }

    /** 比率：base 为 0 时返回 null（页面显示 —，而不是一个假的 0%） */
    private static BigDecimal percent(BigDecimal part, BigDecimal base) {
        if (base == null || base.signum() == 0) {
            return null;
        }
        return part.multiply(HUNDRED).divide(base, PCT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
