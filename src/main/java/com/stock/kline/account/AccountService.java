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
 * 我的账户：账户 / 持仓 / 操作记录的业务核心（需求 v1.5.1 第 ③ 条、v1.5.2 第 ④ 条）。
 *
 * <p>结构是两层的（参考同花顺）：
 * <ol>
 *   <li><b>一级 —— 账户</b>：一个用户可以有多本账，每本账有名字、期初市值与可用金额，
 *       彼此完全隔离；{@link #overview(long)} 给一级列表用，每本账一行 + 全部合计；</li>
 *   <li><b>二级 —— 账户下的持仓 / 盈亏 / 操作记录</b>：全部方法都以 {@code accountId} 定位，
 *       {@link #summary(long, long)} 给二级详情用。</li>
 * </ol>
 *
 * <p>另外两块职责保持不变：
 * <ul>
 *   <li><b>收益计算</b>：现价与昨收用新浪实时行情现取（与盯盘同一条链路），算完不落盘；</li>
 *   <li><b>操作记录</b>：买入 / 卖出实时生效、不落盘（只留内存，见 {@link TradeRecord}），
 *       买入把标的加入盯盘，卖出后<b>所有账户都不再持有</b>该标的才移出盯盘。</li>
 * </ul>
 *
 * <p>用户第一次进来时自动建一本「默认账户」—— 与 v1.5.1 的体验保持连续：
 * 老用户升级后看到的就是原来那份数据外面套了一层账户外壳。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用户没有账户时自动建的那本账的名字 */
    public static final String DEFAULT_ACCOUNT_NAME = "默认账户";

    /** 单账户最多持仓条数：页面一次查一轮行情，太多既慢也容易被行情接口限流 */
    private static final int MAX_POSITIONS = 200;
    /** 每个用户最多账户数：「多本账」是为了分账，不是当数据库用 */
    private static final int MAX_ACCOUNTS = 10;
    /** 账户名称最大长度 */
    private static final int MAX_NAME_LEN = 32;
    /** 每个账户保留的操作记录条数上限（内存数据，不落盘，超出丢最旧的） */
    private static final int MAX_RECORDS = 200;

    /** 金额口径：一律四位小数存储，展示时前端保留两位 */
    private static final int MONEY_SCALE = 4;
    /** 比率口径：百分比保留两位小数 */
    private static final int PCT_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_INIT_VALUE = BigDecimal.valueOf(1_000_000_000_000L);

    private final AccountRepository repository;
    private final ObjectProvider<SinaQuoteClient> quoteClientProvider;
    private final ObjectProvider<WatchConfigStore> watchStoreProvider;
    private final ObjectProvider<StockDirectory> directoryProvider;

    /** userId:accountId → 该账户的操作记录（新的在前）；<b>刻意不落盘</b> */
    private final ConcurrentMap<String, List<TradeRecord>> records = new ConcurrentHashMap<>();
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

    /* ==================== 账户（一级） ==================== */

    /** 某个用户的全部账户，按账户 id 升序；一本都没有时自动建一本默认账户 */
    public List<Account> listAccounts(long userId) {
        List<Account> list = repository.listAccounts(userId);
        if (!list.isEmpty()) {
            return list;
        }
        Account created = repository.createAccount(newAccount(userId, DEFAULT_ACCOUNT_NAME));
        List<Account> fresh = new ArrayList<>();
        fresh.add(created);
        return fresh;
    }

    /**
     * 账户列表视图：每本账一行 + 全部账户合计。
     *
     * <p>行情只按<b>去重后的标的</b>取一轮，再分给各本账用 —— 同一只标的被多本账持有也只取一次。
     */
    public AccountOverview overview(long userId) {
        List<Account> accounts = listAccounts(userId);

        Map<Long, List<Position>> byAccount = new LinkedHashMap<>();
        Map<String, String> wanted = new LinkedHashMap<>();
        for (Account a : accounts) {
            List<Position> positions = repository.listPositions(userId, a.getId());
            byAccount.put(a.getId(), positions);
            for (Position p : positions) {
                if (p.getCode() != null) {
                    wanted.putIfAbsent(p.getCode(),
                            p.getName() == null || p.getName().trim().isEmpty() ? p.getCode() : p.getName());
                }
            }
        }
        Map<String, Quote> quotes = fetchQuotes(wanted);

        AccountOverview out = new AccountOverview();
        List<AccountView> views = new ArrayList<>(accounts.size());
        boolean dayKnown = true;
        BigDecimal preCloseValue = BigDecimal.ZERO;

        for (Account a : accounts) {
            List<Position> positions = byAccount.getOrDefault(a.getId(), new ArrayList<>());
            Calc c = calc(positions, quotes);

            AccountView v = new AccountView();
            v.setId(a.getId());
            v.setName(a.getName());
            v.setInitValue(money(a.getInitValue()));
            v.setCash(money(a.getCash()));
            v.setPositionValue(money(c.positionValue));
            v.setTotalValue(money(c.positionValue.add(nz(a.getCash()))));
            v.setDayProfit(money(c.dayProfit));
            v.setDayProfitPercent(percent(c.dayProfit, c.preCloseValue));
            v.setTotalProfit(money(c.positionValue.add(nz(a.getCash())).subtract(nz(a.getInitValue()))));
            v.setTotalProfitPercent(percent(c.positionValue.add(nz(a.getCash())).subtract(nz(a.getInitValue())),
                    nz(a.getInitValue())));
            v.setPositionCount(positions.size());
            v.setRemark(a.getRemark());
            v.setUpdateTime(a.getUpdateTime());
            views.add(v);

            out.setInitValue(out.getInitValue().add(nz(a.getInitValue())));
            out.setCash(out.getCash().add(nz(a.getCash())));
            out.setPositionValue(out.getPositionValue().add(c.positionValue));
            out.setTotalValue(out.getTotalValue().add(c.positionValue).add(nz(a.getCash())));
            out.setDayProfit(out.getDayProfit().add(c.dayProfit));
            out.setPositionCount(out.getPositionCount() + positions.size());
            if (!c.dayKnown) {
                dayKnown = false;
            }
            preCloseValue = preCloseValue.add(c.preCloseValue);
        }

        out.setAccounts(views);
        out.setAccountCount(views.size());
        out.setInitValue(money(out.getInitValue()));
        out.setCash(money(out.getCash()));
        out.setPositionValue(money(out.getPositionValue()));
        out.setTotalValue(money(out.getTotalValue()));
        out.setDayProfit(money(out.getDayProfit()));
        out.setDayProfitPercent(dayKnown ? percent(out.getDayProfit(), preCloseValue) : null);
        out.setTotalProfit(money(out.getTotalValue().subtract(out.getInitValue())));
        out.setTotalProfitPercent(percent(out.getTotalProfit(), out.getInitValue()));
        return out;
    }

    /** 账户 id：0 / 负数 / 不存在都回落到该用户的第一本账（不存在就建一本） */
    public long resolveAccountId(long userId, Long accountId) {
        if (accountId != null && accountId > 0) {
            Account a = repository.findAccount(userId, accountId);
            if (a != null) {
                return a.getId();
            }
        }
        return listAccounts(userId).get(0).getId();
    }

    /** 取某个账户；accountId 无效时回落到默认账户，一本都没有就现建 */
    public Account getAccount(long userId, Long accountId) {
        return getAccountRequired(userId, resolveAccountId(userId, accountId));
    }

    /** 取某个账户：accountId 必须存在且属于该用户，否则报错（写操作用） */
    public Account getAccountRequired(long userId, long accountId) {
        Account a = repository.findAccount(userId, accountId);
        if (a == null) {
            throw new IllegalArgumentException("账户不存在或已被删除，请刷新页面");
        }
        return a;
    }

    /** 新建一本账 */
    public Account createAccount(long userId, String name, BigDecimal initValue, BigDecimal cash) {
        List<Account> exist = listAccounts(userId);
        if (exist.size() >= MAX_ACCOUNTS) {
            throw new IllegalArgumentException("最多 " + MAX_ACCOUNTS + " 本账户");
        }
        String clean = checkName(name, exist, 0);
        BigDecimal init = checkInit(initValue);
        Account a = newAccount(userId, clean);
        a.setInitValue(init);
        a.setCash(nz(cash).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        String now = LocalDateTime.now().format(DF);
        a.setCreateTime(now);
        a.setUpdateTime(now);
        return repository.createAccount(a);
    }

    /**
     * 保存账户设置（名称 / 期初市值 / 可用金额 / 备注）。
     *
     * <p>可用金额允许为负（融资 / 手工记账场景），但页面会提示；
     * 期初市值 < 0 属于明显误填，直接拒绝。
     */
    public Account saveAccount(long userId, long accountId, Account incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("账户内容不能为空");
        }
        Account a = getAccountRequired(userId, accountId);
        List<Account> exist = listAccounts(userId);

        BigDecimal init = checkInit(incoming.getInitValue());
        BigDecimal cash = nz(incoming.getCash());
        String name = incoming.getName();
        if (name == null || name.trim().isEmpty()) {
            // 页面只改余额没动名字时保持原名
            name = a.getName();
        } else {
            name = checkName(name, exist, accountId);
        }

        a.setName(name);
        a.setInitValue(init);
        a.setCash(cash.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        a.setRemark(incoming.getRemark());
        a.setUpdateTime(LocalDateTime.now().format(DF));
        repository.saveAccount(a);
        return a;
    }

    /** 期初市值与可用金额一起重设（「重置账户」用，持仓不动） */
    public Account resetAccount(long userId, long accountId, BigDecimal initValue, BigDecimal cash) {
        Account a = getAccountRequired(userId, accountId);
        Account incoming = newAccount(userId, a.getName());
        incoming.setInitValue(initValue);
        incoming.setCash(cash);
        return saveAccount(userId, accountId, incoming);
    }

    /** 删除一本账（连同它的持仓与内存里的操作记录）；最后一本不让删 */
    public void deleteAccount(long userId, long accountId) {
        Account a = getAccountRequired(userId, accountId);
        if (listAccounts(userId).size() <= 1) {
            throw new IllegalArgumentException("至少要保留一本账户，不能删掉最后一个");
        }
        List<Position> positions = repository.listPositions(userId, accountId);
        repository.deleteAccount(userId, accountId);
        records.remove(key(userId, accountId));
        for (Position p : positions) {
            watchPruneIfUnheld(userId, p.getCode());
        }
        log.info("[account] 用户 {} 删除账户 {}（{}），原持仓 {} 条", userId, accountId, a.getName(), positions.size());
    }

    /* ==================== 持仓（二级） ==================== */

    public List<Position> listPositions(long userId, long accountId) {
        return repository.listPositions(userId, resolveAccountId(userId, accountId));
    }

    /**
     * 新增或更新一条持仓。
     *
     * <p>「同一本账里同一只标的只能有一条持仓」是硬约束：重复录入会让成本价与数量对不上账。
     * 所以这里先按代码去重 —— 提交的代码已存在且不是同一条时，直接报错让用户改数量。
     */
    public Position savePosition(long userId, long accountId, Position incoming) {
        if (incoming == null) {
            throw new IllegalArgumentException("持仓内容不能为空");
        }
        long aid = resolveAccountId(userId, accountId);
        getAccountRequired(userId, aid);
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
        List<Position> exist = repository.listPositions(userId, aid);
        for (Position one : exist) {
            boolean sameRow = incoming.getId() != null && incoming.getId().equals(one.getId());
            if (!sameRow && code.equals(one.getCode())) {
                throw new IllegalArgumentException(one.getName() + "(" + code + ") 已有持仓，请直接修改数量");
            }
        }
        if (incoming.getId() == null && exist.size() >= MAX_POSITIONS) {
            throw new IllegalArgumentException("单本账户最多 " + MAX_POSITIONS + " 条持仓");
        }
        String now = LocalDateTime.now().format(DF);
        Position p = new Position();
        p.setId(incoming.getId());
        p.setUserId(userId);
        p.setAccountId(aid);
        p.setCode(code);
        p.setName(nameOf(code, incoming.getName()));
        p.setCostPrice(cost.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        p.setQuantity(qty);
        p.setCreateTime(now);
        p.setUpdateTime(now);
        return repository.savePosition(p);
    }

    /** 删除一条持仓 */
    public void deletePosition(long userId, long accountId, long id) {
        long aid = resolveAccountId(userId, accountId);
        repository.deletePosition(userId, aid, id);
    }

    /** 清空持仓（账户设置保留） */
    public void clearPositions(long userId, long accountId) {
        long aid = resolveAccountId(userId, accountId);
        repository.deleteAllPositions(userId, aid);
    }

    /* ==================== 操作记录（实时生效、不落盘） ==================== */

    /**
     * 记一笔买 / 卖，并<b>立即</b>作用到该账户的持仓、可用金额与盯盘名单上。
     *
     * <p><b>价格口径</b>：
     * 已有持仓时一律取该标的的<b>持仓成本价</b>（卖出也按成本价回款），
     * 这样「买 100 股 → 卖 100 股」能精确回到原余额，不会因为盘中价格跳动而对不上账。
     * 买入一只<b>尚无持仓</b>的标的时，价格由 {@code price} 指定 —— 页面会预填该标的的
     * 实时行情价（取不到则留空由用户手填），用户也可以改成自己的实际成交价。
     *
     * <p><b>买入 = 建仓 + 加盯盘</b>：这正是需求里「买入则加入盯盘」的落点 ——
     * 标的进盯盘名单的前提就是用户买了它，所以买入允许新建持仓（{@code price} 必填）；
     * 卖出则要求持仓已存在且数量足够，清仓（卖出后剩 0）时如果别的账户也不再持有该标的，
     * 就把它移出盯盘。
     */
    public TradeRecord addRecord(long userId, long accountId, String action, String code, String name,
                                 Integer quantity, BigDecimal price) {
        long aid = resolveAccountId(userId, accountId);
        Account acct = getAccountRequired(userId, aid);

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

        List<Position> positions = repository.listPositions(userId, aid);
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
                throw new IllegalArgumentException("单本账户最多 " + MAX_POSITIONS + " 条持仓");
            }
            unit = given;
        } else {
            throw new IllegalArgumentException("这本账户里还没有 " + c + " 的持仓，无法卖出");
        }
        unit = unit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal turnover = unit.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cash = nz(acct.getCash());

        Position pos;
        if (buy) {
            if (hit == null) {
                // 新建持仓：成本价 = 本次成交价，数量 = 本次数量
                Position fresh = new Position();
                fresh.setUserId(userId);
                fresh.setAccountId(aid);
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
                repository.deletePosition(userId, aid, hit.getId());
            } else {
                hit.setQuantity(left);
                hit.setUpdateTime(LocalDateTime.now().format(DF));
                repository.savePosition(hit);
            }
            pos = hit;
            // 卖出：按成本价与数量加回可用金额
            cash = cash.add(turnover);
            if (left == 0) {
                watchPruneIfUnheld(userId, hit.getCode());
            }
        }
        saveAccountCash(acct, cash);

        TradeRecord r = new TradeRecord();
        r.setId(recordSeq.incrementAndGet());
        r.setUserId(userId);
        r.setAccountId(aid);
        r.setAction(act);
        r.setCode(c);
        r.setName(pos.getName());
        r.setPrice(unit);
        r.setQuantity(qty);
        r.setTurnover(turnover);
        r.setCashAfter(cash);
        r.setTime(LocalDateTime.now().format(DF));
        pushRecord(userId, aid, r);

        log.info("[account] 用户 {} 账户 {} {} {} {} 股，价格 {}，成交额 {}，可用金额 {}",
                userId, aid, buy ? "买入" : "卖出",
                r.getName(), qty, r.getPrice(), turnover, cash);
        return r;
    }

    /** 某个账户的操作记录（新的在前，不落盘、进程重启即清空） */
    public List<TradeRecord> listRecords(long userId, long accountId) {
        List<TradeRecord> list = records.get(key(userId, resolveAccountId(userId, accountId)));
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    /**
     * 给「记一笔操作」表单预填价格：已有持仓返回其成本价，否则取该标的实时行情价。
     *
     * <p>取不到行情时价格返回 null，页面留空让用户自己填实际成交价。
     */
    public BigDecimal suggestPrice(long userId, long accountId, String code) {
        String c = normalizeCode(code);
        if (c == null) {
            return null;
        }
        long aid = resolveAccountId(userId, accountId);
        for (Position p : repository.listPositions(userId, aid)) {
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

    /** 清空某个账户的操作记录（不影响持仓与余额） */
    public void clearRecords(long userId, long accountId) {
        records.remove(key(userId, resolveAccountId(userId, accountId)));
    }

    /* ==================== 汇总（收益计算） ==================== */

    /** 某个账户的汇总：账户设置 + 持仓明细（含现价、当日/总收益）+ 账户级收益 */
    public AccountSummary summary(long userId, long accountId) {
        Account acct = getAccountRequired(userId, resolveAccountId(userId, accountId));
        List<Position> positions = repository.listPositions(userId, acct.getId());
        Calc c = calc(positions, fetchQuotes(wantedOf(positions)));

        AccountSummary s = new AccountSummary();
        s.setAccountId(acct.getId());
        s.setAccountName(acct.getName());
        s.setInitValue(nz(acct.getInitValue()));
        s.setCash(nz(acct.getCash()));
        s.setRemark(acct.getRemark());
        s.setPositions(c.views);
        s.setPositionValue(money(c.positionValue));
        s.setPositionCost(money(c.positionCost));
        s.setPositionProfit(money(c.positionProfit));
        s.setTotalValue(money(c.positionValue.add(nz(acct.getCash()))));
        s.setTotalProfit(money(c.positionValue.add(nz(acct.getCash())).subtract(nz(acct.getInitValue()))));
        s.setTotalProfitPercent(percent(s.getTotalProfit(), nz(acct.getInitValue())));
        s.setDayProfit(money(c.dayProfit));
        s.setDayProfitPercent(c.dayKnown ? percent(c.dayProfit, c.preCloseValue) : null);
        return s;
    }

    /**
     * 全部账户合起来的汇总（首页「我的账户」区块用）。
     *
     * <p>明细把各本账的持仓拼在一起，但每条带 {@code accountId} / {@code accountName}，
     * 首页只展示前几条，不承担编辑职责。
     */
    public AccountSummary summary(long userId) {
        List<Account> accounts = listAccounts(userId);
        Map<Long, List<Position>> byAccount = new LinkedHashMap<>();
        Map<String, String> wanted = new LinkedHashMap<>();
        for (Account a : accounts) {
            List<Position> positions = repository.listPositions(userId, a.getId());
            byAccount.put(a.getId(), positions);
            for (Position p : positions) {
                if (p.getCode() != null) {
                    wanted.putIfAbsent(p.getCode(),
                            p.getName() == null || p.getName().trim().isEmpty() ? p.getCode() : p.getName());
                }
            }
        }
        Map<String, Quote> quotes = fetchQuotes(wanted);

        AccountSummary s = new AccountSummary();
        s.setAccountId(0);
        s.setAccountName(accounts.size() == 1 ? accounts.get(0).getName() : "全部账户");
        boolean dayKnown = true;
        List<AccountSummary.PositionView> views = new ArrayList<>();
        BigDecimal preCloseValue = BigDecimal.ZERO;
        BigDecimal positionValue = BigDecimal.ZERO;
        BigDecimal positionCost = BigDecimal.ZERO;
        BigDecimal positionProfit = BigDecimal.ZERO;
        BigDecimal dayProfit = BigDecimal.ZERO;
        BigDecimal initValue = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;

        for (Account a : accounts) {
            List<Position> positions = byAccount.getOrDefault(a.getId(), new ArrayList<>());
            Calc c = calc(positions, quotes);
            for (AccountSummary.PositionView v : c.views) {
                v.setAccountId(a.getId());
                v.setAccountName(a.getName());
                views.add(v);
            }
            positionValue = positionValue.add(c.positionValue);
            positionCost = positionCost.add(c.positionCost);
            positionProfit = positionProfit.add(c.positionProfit);
            dayProfit = dayProfit.add(c.dayProfit);
            preCloseValue = preCloseValue.add(c.preCloseValue);
            initValue = initValue.add(nz(a.getInitValue()));
            cash = cash.add(nz(a.getCash()));
            if (!c.dayKnown) {
                dayKnown = false;
            }
        }

        s.setInitValue(money(initValue));
        s.setCash(money(cash));
        s.setPositions(views);
        s.setPositionValue(money(positionValue));
        s.setPositionCost(money(positionCost));
        s.setPositionProfit(money(positionProfit));
        s.setTotalValue(money(positionValue.add(cash)));
        s.setTotalProfit(money(positionValue.add(cash).subtract(initValue)));
        s.setTotalProfitPercent(percent(s.getTotalProfit(), initValue));
        s.setDayProfit(money(dayProfit));
        s.setDayProfitPercent(dayKnown ? percent(dayProfit, preCloseValue) : null);
        return s;
    }

    /* ==================== 内部实现 ==================== */

    /** 一次账户级收益计算的结果：只有数字与持仓视图，不含账户设置 */
    private static final class Calc {
        BigDecimal positionValue = BigDecimal.ZERO;
        BigDecimal positionCost = BigDecimal.ZERO;
        BigDecimal positionProfit = BigDecimal.ZERO;
        BigDecimal dayProfit = BigDecimal.ZERO;
        BigDecimal preCloseValue = BigDecimal.ZERO;
        boolean dayKnown = true;
        List<AccountSummary.PositionView> views = new ArrayList<>();
    }

    /**
     * 算一本账的收益。
     *
     * <p>行情取不到的持仓按成本价兜底并标记 {@code quoted=false}，
     * 这样页面既能看到数字（不空），也知道哪个是兜底值。
     */
    private Calc calc(List<Position> positions, Map<String, Quote> quotes) {
        Calc c = new Calc();
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
                    c.dayProfit = c.dayProfit.add(dayProfitOne);
                    c.preCloseValue = c.preCloseValue.add(preClose.multiply(qty));
                } else {
                    c.dayKnown = false;
                }
            } else {
                c.dayKnown = false;
            }

            c.positionValue = c.positionValue.add(marketValue);
            c.positionCost = c.positionCost.add(costValue);
            c.positionProfit = c.positionProfit.add(profit);
            c.views.add(v);
        }
        return c;
    }

    /** 持仓集合去重后的「代码 → 名称」表（行情请求用） */
    private static Map<String, String> wantedOf(List<Position> positions) {
        Map<String, String> wanted = new LinkedHashMap<>();
        for (Position p : positions) {
            if (p.getCode() != null) {
                wanted.putIfAbsent(p.getCode(),
                        p.getName() == null || p.getName().trim().isEmpty() ? p.getCode() : p.getName());
            }
        }
        return wanted;
    }

    /** 批量取现价：有行情客户端时逐个取，取不到的标的直接缺省（调用侧按成本价兜底） */
    private Map<String, Quote> fetchQuotes(Map<String, String> wanted) {
        Map<String, Quote> out = new LinkedHashMap<>();
        SinaQuoteClient client = quoteClientProvider.getIfAvailable();
        if (client == null || wanted.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            try {
                Quote q = client.fetch(new WatchTarget(e.getValue(), e.getKey()));
                if (q != null) {
                    out.put(e.getKey(), q);
                }
            } catch (Exception ex) {
                // 单只取不到不影响其它持仓的收益展示
                log.warn("[account] 持仓行情取数失败 {}: {}", e.getKey(), ex.getMessage());
            }
        }
        return out;
    }

    /** 更新可用金额（账户其余字段不动） */
    private void saveAccountCash(Account acct, BigDecimal cash) {
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

    /**
     * 该标的在所有账户里都不再持有后，把它移出盯盘。
     *
     * <p>盯盘名单挂在用户维度，所以「清仓」的判据必须是<b>跨账户</b>的：
     * A 账清掉 600000、B 账还拿着时，不能顺手把盯盘摘掉。
     */
    private void watchPruneIfUnheld(long userId, String code) {
        if (code == null) {
            return;
        }
        for (Account a : repository.listAccounts(userId)) {
            for (Position p : repository.listPositions(userId, a.getId())) {
                if (code.equals(p.getCode())) {
                    return;
                }
            }
        }
        watchRemove(userId, code);
    }

    /** 把标的从该用户的盯盘名单移除 */
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

    private void pushRecord(long userId, long accountId, TradeRecord r) {
        String k = key(userId, accountId);
        List<TradeRecord> fresh = new ArrayList<>();
        fresh.add(r);
        List<TradeRecord> old = records.get(k);
        if (old != null) {
            for (TradeRecord one : old) {
                if (fresh.size() >= MAX_RECORDS) {
                    break;
                }
                fresh.add(one);
            }
        }
        records.put(k, fresh);
    }

    private static String key(long userId, long accountId) {
        return userId + ":" + accountId;
    }

    /** 账户名判空 / 判长 / 判重（skipId 是要改的那本账，判重时跳过自己） */
    private static String checkName(String name, List<Account> exist, long skipId) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("账户名称不能为空");
        }
        if (clean.length() > MAX_NAME_LEN) {
            throw new IllegalArgumentException("账户名称最多 " + MAX_NAME_LEN + " 个字");
        }
        for (Account a : exist) {
            if (a.getId() != skipId && clean.equals(a.getName())) {
                throw new IllegalArgumentException("已经有叫「" + clean + "」的账户了，换个名字");
            }
        }
        return clean;
    }

    /** 期初市值校验：负数与明显误填直接拒绝 */
    private static BigDecimal checkInit(BigDecimal initValue) {
        BigDecimal init = nz(initValue);
        if (init.signum() < 0) {
            throw new IllegalArgumentException("账户期初市值不能为负数");
        }
        if (init.compareTo(MAX_INIT_VALUE) > 0) {
            throw new IllegalArgumentException("账户期初市值超过 1 万亿，请确认是否填错");
        }
        return init.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static Account newAccount(long userId, String name) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName(name);
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

    private static BigDecimal money(BigDecimal v) {
        return nz(v).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
