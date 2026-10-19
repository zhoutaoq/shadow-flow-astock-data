package io.github.zhoutaoq.shadowflow.biz.account;

import io.github.zhoutaoq.shadowflow.biz.support.StockDirectory;
import io.github.zhoutaoq.shadowflow.biz.watch.Quote;
import io.github.zhoutaoq.shadowflow.biz.watch.SinaQuoteClient;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchConfigStore;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchSettings;
import io.github.zhoutaoq.shadowflow.biz.watch.WatchTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li><b>操作记录</b>：买入 / 卖出实时生效，<b>v1.5.6 起随账户、持仓一起落盘</b>
 *       （见 {@link TradeRecord}），买入把标的加入盯盘，
 *       卖出后<b>所有账户都不再持有</b>该标的才移出盯盘。</li>
 * </ul>
 *
 * <p>用户第一次进来时自动建一本「默认账户」—— 与 v1.5.1 的体验保持连续：
 * 老用户升级后看到的就是原来那份数据外面套了一层账户外壳。
 *
 * <h3>v1.5.5：当日盈亏口径与买卖记账口径（重要）</h3>
 *
 * <p><b>1. 当日盈亏不再一律拿昨收当基准。</b>按份额拆开算，基准价取哪一个看份额来源：
 * <ol>
 *   <li><b>当天买入的份额</b>（操作记录里勾了「当天买入」）→ 基准价 = <b>当日买入均价</b>。
 *       拿昨收去比会把「买入价与昨收之间的那段」当成自己赚的，这正是 v1.5.4 的错处；</li>
 *   <li><b>隔夜持仓的份额</b> → 基准价 = <b>昨收</b>（与各家券商口径一致）；</li>
 *   <li><b>页面直接配置的持仓</b>且<b>建仓日就是今天</b> → 整条按<b>成本价</b>为基准
 *       （这种持仓没有当日买入均价可用，成本价就是它今天的起点）。</li>
 * </ol>
 * 三类份额混在一只标的上时按份额加权出一个「当量基准价」，页面看得到折算后的均价与口径说明。
 *
 * <p><b>2. 当天卖出的部分单列「当日已实现盈亏」</b>，与持仓浮动一起构成账户当日收益：
 * <pre>
 * 账户当日收益 = Σ 持仓浮动当日盈亏 + 当日已实现盈亏
 * 当日已实现   = (卖出成交价 - 该份额的基准价) × 卖出数量
 * </pre>
 * 落盘在 {@link Account#getDayRealizedProfit()}，日期不是今天就按 0 参与计算，
 * 所以不需要定时任务归零。持仓被清空后这份已实现仍然留在账户上，不会丢。
 *
 * <p><b>3. 买卖记账改用真实成交价，成本价改为加权平均。</b>v1.5.4 之前买入/卖出
 * 一律按持仓成本价动可用金额，好处是「买 N 股再卖 N 股」能精确回到原余额，
 * 代价是卖出价恒等于成本价、已实现盈亏恒为 0，当日盈亏根本算不出卖出那部分。
 * 现在：买入按成交价扣减可用金额并按<b>加权平均</b>重算成本价，卖出按成交价加回，
 * 于是「买入瞬间账户总市值不变」「卖出瞬间的差额正好是已实现盈亏」两条都成立。
 *
 * <h3>v1.6.7：未开盘的市值口径（重要）</h3>
 *
 * <p>新浪在集合竞价前与停牌时把现价字段写成 0。旧逻辑把这当成「没有行情」，
 * 于是一律回落到<b>成本价</b>算市值 —— 每天开盘前看到的持仓市值都是成本市值，
 * 当日收益恒为 0，看起来像「页面没刷新」。现在估值价按
 * <b>现价 → 昨收 → 进程内最近一次有效价 → 成本价</b> 依次取第一个能用的，
 * 并把口径(`priceBasis`)一起下发，页面据此打标。
 *
 * <p>做法上分两层：{@link SinaQuoteClient#fetchForValuation} 负责「现价为 0 时用昨收顶上」，
 * 本类负责「连接口都没通时用最近价兜底」。盯盘链路仍走 {@code fetch}，
 * 语义不变 —— 那里要的是「现在有没有有效成交价」，不是「值多少钱」。
 *
 * <p>分钟走势（{@link AccountTrendService}）刻意只调用本类的 {@link #summary(long, long)}：
 * 曲线上的每个点都必须等于「那一刻打开页面看到的总市值」，口径只有一处，不会各算各的。
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 用户没有账户时自动建的那本账的名字 */
    public static final String DEFAULT_ACCOUNT_NAME = "默认账户";

    /** 单账户最多持仓条数：页面一次查一轮行情，太多既慢也容易被行情接口限流 */
    static final int MAX_POSITIONS = 200;
    /** 每个用户最多账户数：「多本账」是为了分账，不是当数据库用 */
    private static final int MAX_ACCOUNTS = 10;
    /** 账户名称最大长度 */
    private static final int MAX_NAME_LEN = 32;
    /** 每个账户保留的操作记录条数上限（超出丢最旧的：记录只增不减会让落盘文件无限膨胀） */
    static final int MAX_RECORDS = 200;

    /** 金额口径：一律四位小数存储，展示时前端保留两位 */
    private static final int MONEY_SCALE = 4;
    /** 比率口径：百分比保留两位小数 */
    private static final int PCT_SCALE = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal MAX_INIT_VALUE = BigDecimal.valueOf(1_000_000_000_000L);

    /** 当日盈亏基准口径（页面直接展示，别在别处再拼字符串） */
    private static final String BASIS_PRE_CLOSE = "昨收";
    private static final String BASIS_DAY_BUY = "当日买入均价";
    private static final String BASIS_OPEN_TODAY = "建仓成本价";
    private static final String BASIS_MIXED = "昨收 + 当日买入均价（按份额折算）";

    /** 建仓日的输入只认 yyyy-MM-dd / yyyyMMdd / yyyy/MM/dd / yyyy.MM.dd */
    private static final DateTimeFormatter YMD_COMPACT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 现价口径说明（v1.6.7）：页面按它打标，用户一眼能看出市值这个数字是怎么来的 */
    private static final String PRICE_LIVE = "现价";
    private static final String PRICE_PRE_CLOSE = "昨收";
    private static final String PRICE_LAST = "最近价";
    private static final String PRICE_COST = "成本价";

    private final AccountRepository repository;
    private final AccountTrendRepository trendRepository;
    private final ObjectProvider<SinaQuoteClient> quoteClientProvider;
    private final ObjectProvider<WatchConfigStore> watchStoreProvider;
    private final ObjectProvider<StockDirectory> directoryProvider;

    /**
     * 最近一次拿到的有效现价（代码 → 价格）。
     *
     * <p>只作估值兜底用：新浪偶发超时 / 限流时，账户市值不该一脚踹回成本价 ——
     * 那等于把「行情断了一秒」显示成「持仓亏了一大截」。进程内缓存，重启即空，
     * 空着时才最后退回成本价。
     */
    private final Map<String, BigDecimal> lastPriceCache = new ConcurrentHashMap<>();

    public AccountService(AccountRepository repository,
                          AccountTrendRepository trendRepository,
                          ObjectProvider<SinaQuoteClient> quoteClientProvider,
                          ObjectProvider<WatchConfigStore> watchStoreProvider,
                          ObjectProvider<StockDirectory> directoryProvider) {
        this.repository = repository;
        this.trendRepository = trendRepository;
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
        String today = todayYmd();

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
        BigDecimal basisValue = BigDecimal.ZERO;

        for (Account a : accounts) {
            List<Position> positions = byAccount.getOrDefault(a.getId(), new ArrayList<>());
            Calc c = calc(positions, quotes, today);
            BigDecimal realized = dayRealizedOf(a, today);
            BigDecimal dayProfit = c.dayProfit.add(realized);

            AccountView v = new AccountView();
            v.setId(a.getId());
            v.setName(a.getName());
            v.setInitValue(money(a.getInitValue()));
            v.setCash(money(a.getCash()));
            v.setPositionValue(money(c.positionValue));
            v.setTotalValue(money(c.positionValue.add(nz(a.getCash()))));
            v.setDayProfit(money(dayProfit));
            v.setDayProfitPercent(percent(dayProfit, c.dayBasisValue));
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
            out.setDayProfit(out.getDayProfit().add(dayProfit));
            out.setPositionCount(out.getPositionCount() + positions.size());
            if (!c.dayKnown) {
                dayKnown = false;
            }
            basisValue = basisValue.add(c.dayBasisValue);
        }

        out.setAccounts(views);
        out.setAccountCount(views.size());
        out.setInitValue(money(out.getInitValue()));
        out.setCash(money(out.getCash()));
        out.setPositionValue(money(out.getPositionValue()));
        out.setTotalValue(money(out.getTotalValue()));
        out.setDayProfit(money(out.getDayProfit()));
        out.setDayProfitPercent(dayKnown ? percent(out.getDayProfit(), basisValue) : null);
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
     * 按导入文件里的「账户情况」新建一本账（需求 v1.5.6 第 ③ 条）。
     *
     * <p>导入<b>不改动</b>已有的任何一本账，只新增 —— 导入出错大不了删掉这本，
     * 用户原来的账户与持仓一行都不动。
     *
     * <p>账户名沿用导出时的名字；重名时自动加「（导入）」「（导入2）」这样的后缀，
     * 免得和现有账户撞名（同一用户下账户名唯一）。名字超长直接截断到上限。
     */
    public Account createAccountForImport(long userId, String name, BigDecimal initValue,
                                          BigDecimal cash, String remark) {
        List<Account> exist = listAccounts(userId);
        if (exist.size() >= MAX_ACCOUNTS) {
            throw new IllegalArgumentException("最多 " + MAX_ACCOUNTS + " 本账户，先删掉一本再导入");
        }
        String base = name == null ? "" : name.trim();
        if (base.isEmpty()) {
            base = DEFAULT_ACCOUNT_NAME;
        }
        if (base.length() > MAX_NAME_LEN) {
            base = base.substring(0, MAX_NAME_LEN);
        }
        Account a = newAccount(userId, uniqueName(base, exist));
        a.setInitValue(checkInit(initValue));
        a.setCash(nz(cash).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        a.setRemark(remark);
        String now = LocalDateTime.now().format(DF);
        a.setCreateTime(now);
        a.setUpdateTime(now);
        return repository.createAccount(a);
    }

    /** 重名时依次试「（导入）」「（导入2）」…；名字已被截到上限时会先让位给后缀 */
    private static String uniqueName(String base, List<Account> exist) {
        if (!nameTaken(base, exist)) {
            return base;
        }
        for (int i = 1; i <= MAX_ACCOUNTS + 1; i++) {
            String suffix = i == 1 ? "（导入）" : "（导入" + i + "）";
            int room = MAX_NAME_LEN - suffix.length();
            String head = base.length() > room ? base.substring(0, Math.max(0, room)) : base;
            String candidate = head + suffix;
            if (!nameTaken(candidate, exist)) {
                return candidate;
            }
        }
        return base;
    }

    private static boolean nameTaken(String name, List<Account> exist) {
        for (Account a : exist) {
            if (name.equals(a.getName())) {
                return true;
            }
        }
        return false;
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

    /** 删除一本账（连同它的持仓与操作记录）；最后一本不让删 */
    public void deleteAccount(long userId, long accountId) {
        Account a = getAccountRequired(userId, accountId);
        if (listAccounts(userId).size() <= 1) {
            throw new IllegalArgumentException("至少要保留一本账户，不能删掉最后一个");
        }
        List<Position> positions = repository.listPositions(userId, accountId);
        repository.deleteAccount(userId, accountId);
        // 走势不跟着账户走会留下永远读不到的孤儿数据（文件模式下还会让文件一直变大）
        if (trendRepository != null) {
            try {
                trendRepository.deleteAccount(userId, accountId);
            } catch (Exception e) {
                log.warn("[account] 账户 {} 的走势清理失败：{}", accountId, e.getMessage());
            }
        }
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
     *
     * <p><b>建仓日（v1.5.5 第 ③ 条）</b>：非必填，页面手填；留空即 null。
     * 另外这个方法是用提交内容<b>重建</b>一行，所以「当天买入池」必须从库里那一行原样带过来 ——
     * 否则用户在页面上改一下数量就会把当天买入的痕迹抹掉、当日盈亏跟着跳。
     *
     * <p><b>可用数量（v1.5.6 第 ① 条）</b>：同样是从头重建，所以先校验
     * {@code 0 ≤ 可用数量 ≤ 持仓数量}（超了就报错，不静默夹）；没提交（null）时沿用库里那一行，
     * 新行没提交就是 null —— 语义是「全部可用」，老前端与老数据都照旧能卖。
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
        Position prev = null;
        for (Position one : exist) {
            boolean sameRow = incoming.getId() != null && incoming.getId().equals(one.getId());
            if (sameRow) {
                prev = one;
            } else if (code.equals(one.getCode())) {
                throw new IllegalArgumentException(one.getName() + "(" + code + ") 已有持仓，请直接修改数量");
            }
        }
        if (incoming.getId() == null && exist.size() >= MAX_POSITIONS) {
            throw new IllegalArgumentException("单本账户最多 " + MAX_POSITIONS + " 条持仓");
        }
        Integer available = incoming.getAvailableQty();
        if (available == null && prev != null) {
            // 没提交（老前端 / 只改了别的字段）就沿用原值，别把用户设过的可用数量抹掉
            available = prev.getAvailableQty();
        }
        if (available != null && (available < 0 || available > qty)) {
            throw new IllegalArgumentException("可用数量要在 0 到持仓数量（" + qty + " 股）之间");
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
        p.setAvailableQty(available);
        p.setOpenDate(checkOpenDate(incoming.getOpenDate()));
        if (prev != null) {
            p.setDayBuyDate(prev.getDayBuyDate());
            p.setDayBuyQty(prev.getDayBuyQty());
            p.setDayBuyAmount(nz(prev.getDayBuyAmount()));
        }
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

    /* ==================== 可用数量日切（v1.5.6 第 ⑥ 条） ==================== */

    /**
     * 把所有用户、所有账户、所有标的的<b>可用数量对齐到持仓数量</b>。
     *
     * <p>语义就是隔夜结算：头一天买进来的份额到了新交易日全部变成可卖。
     * 由 {@code AvailableQtyResetTask} 每天 00:00（北京时间）触发，
     * 也做成 public 便于单独验证（不经过 HTTP，避免为一个内部动作多开一个写接口）。
     *
     * <p>已经是「可用 = 持仓」的持仓不动、不重写文件：每天全量重写一遍
     * 会把落盘文件的 mtime 全刷一遍，出问题时反而看不出到底哪几行真的变过。
     *
     * @return 实际被改动的持仓条数
     */
    public int resetAvailableQty() {
        int users = 0;
        int scanned = 0;
        int changed = 0;
        for (Long userId : repository.listUserIds()) {
            users++;
            for (Account a : repository.listAccounts(userId)) {
                for (Position p : repository.listPositions(userId, a.getId())) {
                    scanned++;
                    Integer avail = p.getAvailableQty();
                    // null 视为「全部可用」（老数据兼容口径），一并落成显式的持仓数量
                    if (avail != null && avail.intValue() == p.getQuantity()) {
                        continue;
                    }
                    p.setAvailableQty(p.getQuantity());
                    repository.savePosition(p);
                    changed++;
                }
            }
        }
        log.info("[account] 可用数量日切完成：{} 个用户 / 扫描 {} 条持仓 / 对齐 {} 条", users, scanned, changed);
        return changed;
    }

    /* ==================== 操作记录（实时生效，v1.5.6 起随账户落盘） ==================== */

    /**
     * 记一笔买 / 卖，并<b>立即</b>作用到该账户的持仓、可用金额、当日盈亏与盯盘名单上。
     *
     * <p><b>价格口径（v1.5.5 起用真实成交价）</b>：{@code price} 是这笔的实际成交价 ——
     * 页面会预填该标的的实时行情价（取不到则留空由用户手填），用户也可以改成自己的成交价。
     * 买入按成交价扣减可用金额、并按<b>加权平均</b>重算成本价；卖出按成交价加回可用金额。
     * 没填价格时回落到持仓成本价（老前端不带价格时行为不变）。
     *
     * <p><b>当天标记 {@code todayFlag}</b>（页面上「当天买入 / 卖的是当天买入的」勾选框，默认勾选）：
     * <ul>
     *   <li>买入 + 勾选 → 这笔量进该持仓的「当天买入池」，
     *       它今后的当日盈亏以<b>当日买入均价</b>为基准，而不是昨收；</li>
     *   <li>买新标的 + 勾选 → 顺手把建仓日也落成今天；</li>
     *   <li>卖出 + 勾选 → 优先卖<b>当天买入</b>那部分，已实现盈亏按当日买入均价算；</li>
     *   <li>卖出 + 不勾 → 优先卖隔夜持仓，已实现盈亏按<b>昨收</b>算；
     *       卖得比隔夜持仓还多时，多出来的部分仍然算在当天买入份额上（A股 T+1 下这本不该发生，
     *       手工记账时却可能出现，这里给出唯一自洽的解释而不是拒绝）。</li>
     * </ul>
     *
     * <p><b>买入 = 建仓 + 加盯盘</b>：这正是需求里「买入则加入盯盘」的落点 ——
     * 标的进盯盘名单的前提就是用户买了它，所以买入允许新建持仓（{@code price} 必填）；
     * 卖出则要求持仓已存在且数量足够，清仓（卖出后剩 0）时如果别的账户也不再持有该标的，
     * 就把它移出盯盘。
     *
     * <h4>v1.5.6：卖出的两道闸 + 可用数量 + 成交日期</h4>
     * <ol>
     *   <li><b>卖出必须真的持有</b>：该账户里没有这只标的的持仓时直接报错。
     *       v1.5.5 及以前只在「没填成交价」这条路径上判过，页面一带上价就走到
     *       {@code hit.getQuantity()} 上空指针 —— 现在把判空提到最前面，
     *       并且把「只能卖可用数量以内的量」也一起收在这里；</li>
     *   <li><b>可用数量是卖出的硬上限</b>：{@code 数量 ≤ usableQty(持仓)}，
     *       买入加、卖出减（见 {@link Position#getAvailableQty()}）；</li>
     *   <li><b>成交日期</b>：{@code tradeDate} 缺省即今天。是今天才动「当天买入池」与账户的
     *       「当日已实现盈亏」；填成过去的日期属于补录，只改持仓与可用金额，不碰当日口径。</li>
     * </ol>
     */
    public TradeRecord addRecord(long userId, long accountId, String action, String code, String name,
                                 Integer quantity, BigDecimal price, Boolean todayFlag, String tradeDate) {
        long aid = resolveAccountId(userId, accountId);
        Account acct = getAccountRequired(userId, aid);
        String today = todayYmd();
        String date = checkTradeDate(tradeDate, today);
        // 不是今天的成交属于补录：不进「当天买入池」、不计当日已实现（那些口径只对今天有效）
        boolean onToday = today.equals(date);
        // 不带标记的老调用按「当天」处理：用户此刻按下「记一笔」，绝大多数就是当天成交
        boolean sameDay = (todayFlag == null || todayFlag) && onToday;

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
        // 卖出守门：必须先真的持有、且有可卖出的数量（v1.5.6 第 ① 条）
        if (!buy) {
            if (hit == null) {
                throw new IllegalArgumentException("这本账户里还没有 " + displayName + " 的持仓，无法卖出");
            }
            int usable = usableQty(hit);
            if (usable <= 0) {
                throw new IllegalArgumentException(displayName + "(" + c + ") 当前没有可卖出的数量");
            }
            if (usable < qty) {
                throw new IllegalArgumentException("卖出 " + qty + " 股超出可用数量 " + usable + " 股");
            }
        }
        BigDecimal given = nz(price);
        BigDecimal unit;
        if (given.signum() > 0) {
            // 页面填了成交价：买卖一律以它为准
            unit = given;
        } else if (hit != null) {
            // 没填又有持仓：回落持仓成本价（老前端行为不变）
            unit = nz(hit.getCostPrice());
        } else if (buy) {
            throw new IllegalArgumentException("买入 " + displayName + " 前请填写成交价");
        } else {
            throw new IllegalArgumentException("这本账户里还没有 " + c + " 的持仓，无法卖出");
        }
        if (buy && hit == null && positions.size() >= MAX_POSITIONS) {
            throw new IllegalArgumentException("单本账户最多 " + MAX_POSITIONS + " 条持仓");
        }
        unit = unit.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal turnover = unit.multiply(BigDecimal.valueOf(qty)).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal cash = nz(acct.getCash());

        Position pos;
        BigDecimal realized = null;
        if (buy) {
            if (hit == null) {
                // 新建持仓：成本价 = 本次成交价，数量 = 本次数量，可用数量同样从 0 起加
                Position fresh = new Position();
                fresh.setUserId(userId);
                fresh.setAccountId(aid);
                fresh.setCode(c);
                fresh.setName(displayName);
                fresh.setCostPrice(unit);
                fresh.setQuantity(qty);
                fresh.setAvailableQty(qty);
                if (sameDay) {
                    fresh.setOpenDate(today);
                    fresh.setDayBuyDate(today);
                    fresh.setDayBuyQty(qty);
                    fresh.setDayBuyAmount(turnover);
                }
                String now = LocalDateTime.now().format(DF);
                fresh.setCreateTime(now);
                fresh.setUpdateTime(now);
                pos = repository.savePosition(fresh);
            } else {
                if (hit.getQuantity() < 1) {
                    throw new IllegalArgumentException("持仓数量异常，请重新配置持仓");
                }
                int usable = usableQty(hit);
                // 加仓：成本价按「老成本市值 + 本次成交额」加权平均重算，
                // 这样买入那一刻账户总市值不变，不会凭空多出一段盈亏
                int newQty = hit.getQuantity() + qty;
                BigDecimal oldValue = nz(hit.getCostPrice()).multiply(BigDecimal.valueOf(hit.getQuantity()));
                BigDecimal newCost = oldValue.add(turnover)
                        .divide(BigDecimal.valueOf(newQty), MONEY_SCALE + 2, RoundingMode.HALF_UP);
                hit.setCostPrice(newCost.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
                hit.setQuantity(newQty);
                hit.setAvailableQty(usable + qty);
                if (sameDay) {
                    rolloverDayBuy(hit, today);
                    hit.setDayBuyQty(hit.getDayBuyQty() + qty);
                    hit.setDayBuyAmount(nz(hit.getDayBuyAmount()).add(turnover));
                    if (isBlank(hit.getOpenDate())) {
                        hit.setOpenDate(today);
                    }
                }
                hit.setUpdateTime(LocalDateTime.now().format(DF));
                pos = repository.savePosition(hit);
            }
            // 买入：按成交价扣减可用金额
            cash = cash.subtract(turnover);
            watchAdd(userId, pos);
        } else {
            int usable = usableQty(hit);
            // 当日已实现：卖出价 vs 基准价（当天买入份额用当日买入均价、隔夜份额用昨收）。
            // 补录（日期不是今天）不算当日已实现 —— 那个数只对今天有意义
            BigDecimal preClose = preCloseOf(c);
            realized = onToday ? realizedOfSell(hit, today, qty, unit, sameDay, preClose) : BigDecimal.ZERO;
            if (onToday) {
                int fromDay = soldFromDay(hit, today, qty, sameDay);
                if (fromDay > 0) {
                    reduceDayPool(hit, today, fromDay);
                }
            }
            int left = hit.getQuantity() - qty;
            if (left == 0) {
                repository.deletePosition(userId, aid, hit.getId());
            } else {
                hit.setQuantity(left);
                hit.setAvailableQty(Math.min(Math.max(0, usable - qty), left));
                hit.setUpdateTime(LocalDateTime.now().format(DF));
                repository.savePosition(hit);
            }
            pos = hit;
            // 卖出：按成交价加回可用金额
            cash = cash.add(turnover);
            if (left == 0) {
                watchPruneIfUnheld(userId, hit.getCode());
            }
        }
        // 已实现盈亏落在账户上（日期不是今天的旧值先作废）；补录不参与当日已实现
        if (onToday && realized != null && realized.signum() != 0) {
            if (!today.equals(acct.getDayRealizedDate())) {
                acct.setDayRealizedDate(today);
                acct.setDayRealizedProfit(BigDecimal.ZERO);
            }
            acct.setDayRealizedProfit(nz(acct.getDayRealizedProfit()).add(realized)
                    .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        }
        saveAccountCash(acct, cash);

        TradeRecord r = new TradeRecord();
        r.setUserId(userId);
        r.setAccountId(aid);
        r.setAction(act);
        r.setCode(c);
        r.setName(pos.getName());
        r.setPrice(unit);
        r.setQuantity(qty);
        r.setTurnover(turnover);
        r.setCashAfter(cash);
        r.setSameDay(sameDay);
        r.setRealizedProfit(realized == null ? BigDecimal.ZERO : realized);
        r.setTradeDate(date);
        r.setTime(LocalDateTime.now().format(DF));
        repository.saveRecord(r);
        repository.trimRecords(userId, aid, MAX_RECORDS);

        log.info("[account] 用户 {} 账户 {} {} {} {} 股，价格 {}，成交额 {}，可用金额 {}{}{}",
                userId, aid, buy ? "买入" : "卖出",
                r.getName(), qty, r.getPrice(), turnover, cash,
                onToday ? "" : "，成交日期 " + date,
                realized == null ? "" : "，当日已实现 " + realized);
        return r;
    }

    /** 某个账户的操作记录（新的在前，与账户、持仓一起落盘，重启不丢） */
    public List<TradeRecord> listRecords(long userId, long accountId) {
        List<TradeRecord> list = repository.listRecords(userId, resolveAccountId(userId, accountId));
        List<TradeRecord> out = new ArrayList<>(list.size());
        for (int i = list.size() - 1; i >= 0; i--) {
            out.add(list.get(i));
        }
        return out;
    }

    /**
     * 给「记一笔」弹窗预填价格：<b>先取该标的的实时行情价</b>（页面上要的是实际成交价的参照），
     * 取不到再回落到该账户的持仓成本价；都拿不到返回 null，页面留空让用户自己填实际成交价。
     *
     * <p>v1.5.4 之前是反过来的（有持仓就直接给成本价）—— 那样填出来的「成交价」恒等于成本价，
     * 当日已实现盈亏也就恒为 0，等于把这次要修的问题又盖了回去。
     */
    public BigDecimal suggestPrice(long userId, long accountId, String code) {
        String c = normalizeCode(code);
        if (c == null) {
            return null;
        }
        SinaQuoteClient client = quoteClientProvider.getIfAvailable();
        if (client != null) {
            try {
                // 估值口径：未开盘时现价是 0，这里要的是昨收，别让预填价退回成本价
                Quote q = client.fetchForValuation(new WatchTarget(c, c));
                if (q != null && q.getPrice() != null && q.getPrice().signum() > 0) {
                    return q.getPrice();
                }
            } catch (Exception e) {
                log.warn("[account] 预填价格取行情失败 {}: {}", c, e.getMessage());
            }
        }
        long aid = resolveAccountId(userId, accountId);
        for (Position p : repository.listPositions(userId, aid)) {
            if (c.equals(p.getCode())) {
                return nz(p.getCostPrice());
            }
        }
        return null;
    }

    /** 清空某个账户的操作记录（不影响持仓与余额） */
    public void clearRecords(long userId, long accountId) {
        repository.clearRecords(userId, resolveAccountId(userId, accountId));
    }

    /* ==================== 汇总（收益计算） ==================== */

    /** 某个账户的汇总：账户设置 + 持仓明细（含现价、当日/总收益）+ 账户级收益 */
    public AccountSummary summary(long userId, long accountId) {
        Account acct = getAccountRequired(userId, resolveAccountId(userId, accountId));
        String today = todayYmd();
        List<Position> positions = repository.listPositions(userId, acct.getId());
        Calc c = calc(positions, fetchQuotes(wantedOf(positions)), today);
        BigDecimal realized = dayRealizedOf(acct, today);
        BigDecimal dayProfit = c.dayProfit.add(realized);

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
        s.setDayProfit(money(dayProfit));
        s.setDayFloatProfit(money(c.dayProfit));
        s.setDayRealizedProfit(money(realized));
        s.setDayProfitPercent(c.dayKnown ? percent(dayProfit, c.dayBasisValue) : null);
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
        String today = todayYmd();
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
        BigDecimal dayBasisValue = BigDecimal.ZERO;
        BigDecimal positionValue = BigDecimal.ZERO;
        BigDecimal positionCost = BigDecimal.ZERO;
        BigDecimal positionProfit = BigDecimal.ZERO;
        BigDecimal dayFloatProfit = BigDecimal.ZERO;
        BigDecimal dayRealizedProfit = BigDecimal.ZERO;
        BigDecimal initValue = BigDecimal.ZERO;
        BigDecimal cash = BigDecimal.ZERO;

        for (Account a : accounts) {
            List<Position> positions = byAccount.getOrDefault(a.getId(), new ArrayList<>());
            Calc c = calc(positions, quotes, today);
            for (AccountSummary.PositionView v : c.views) {
                v.setAccountId(a.getId());
                v.setAccountName(a.getName());
                views.add(v);
            }
            positionValue = positionValue.add(c.positionValue);
            positionCost = positionCost.add(c.positionCost);
            positionProfit = positionProfit.add(c.positionProfit);
            dayFloatProfit = dayFloatProfit.add(c.dayProfit);
            dayRealizedProfit = dayRealizedProfit.add(dayRealizedOf(a, today));
            dayBasisValue = dayBasisValue.add(c.dayBasisValue);
            initValue = initValue.add(nz(a.getInitValue()));
            cash = cash.add(nz(a.getCash()));
            if (!c.dayKnown) {
                dayKnown = false;
            }
        }
        BigDecimal dayProfit = dayFloatProfit.add(dayRealizedProfit);

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
        s.setDayFloatProfit(money(dayFloatProfit));
        s.setDayRealizedProfit(money(dayRealizedProfit));
        s.setDayProfitPercent(dayKnown ? percent(dayProfit, dayBasisValue) : null);
        return s;
    }

    /* ==================== 内部实现 ==================== */

    /** 一次账户级收益计算的结果：只有数字与持仓视图，不含账户设置 */
    private static final class Calc {
        BigDecimal positionValue = BigDecimal.ZERO;
        BigDecimal positionCost = BigDecimal.ZERO;
        BigDecimal positionProfit = BigDecimal.ZERO;
        /** 持仓浮动当日盈亏（不含当天卖出的已实现） */
        BigDecimal dayProfit = BigDecimal.ZERO;
        /** 当日基准市值 = Σ(当量基准价 × 数量)，当日收益率的分母 */
        BigDecimal dayBasisValue = BigDecimal.ZERO;
        boolean dayKnown = true;
        List<AccountSummary.PositionView> views = new ArrayList<>();
    }

    /**
     * 一条持仓的当日盈亏基准：份额可能来自不同来源，这里折算成一个当量基准价。
     *
     * <p>比例与口径分开存：{@code price} 用来算钱（份额加权后的当量价），
     * {@code label} 用来在页面上说清楚这个价是怎么来的。
     */
    private static final class DayBasis {
        /** 当量基准价（元）：Σ(各来源基准价 × 份额) ÷ 总份额 */
        BigDecimal price;
        /** 口径说明，页面直接展示 */
        String label;
        /** 其中属于「当天买入」的份额 */
        int dayQty;
        /** 当日买入均价（元），拿不到为 null */
        BigDecimal dayBuyAvg;
    }

    /**
     * 算一本账的收益。
     *
     * <p>现价取不到时不再一律回落到成本价（v1.6.7）：按
     * <b>现价 → 昨收 → 进程内最近一次有效价 → 成本价</b> 的顺序取第一个能用的，
     * 并把口径写进 {@code priceBasis}。
     *
     * <p>为什么必须这么改：新浪在集合竞价前、停牌时把现价写成 0，旧逻辑按「没有行情」处理，
     * 于是「我的账户」每天开盘前看到的持仓市值全是成本市值、当日收益恒为 0 ——
     * 用户原话是「用成本价算，太傻逼了」。昨收与最近价都是真实成交过的价格，
     * 未开盘时用它算市值才是各家的通行做法；只有连这些都没有（进程刚起来、行情全挂）才退回成本价。
     *
     * <p>当日盈亏仍按 {@link #dayBasisOf} 给出的当量基准价算 —— 未开盘时现价 = 昨收，
     * 当日收益自然是 0，不会凭空多出一段盈亏。
     */
    private Calc calc(List<Position> positions, Map<String, Quote> quotes, String today) {
        Calc c = new Calc();
        for (Position p : positions) {
            Quote q = quotes.get(p.getCode());
            AccountSummary.PositionView v = new AccountSummary.PositionView();
            v.setId(p.getId());
            v.setCode(p.getCode());
            v.setName(p.getName());
            v.setCostPrice(p.getCostPrice());
            v.setQuantity(p.getQuantity());
            // 可用数量按折算后的值下发（老数据没设过 = 全部可用），页面不必再判空
            v.setAvailableQty(usableQty(p));
            v.setOpenDate(p.getOpenDate());

            BigDecimal cost = nz(p.getCostPrice());
            BigDecimal qty = BigDecimal.valueOf(p.getQuantity());
            BigDecimal costValue = cost.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            PricePick pick = pickPrice(p, q, cost);
            BigDecimal price = pick.price;
            BigDecimal marketValue = price.multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            BigDecimal profit = marketValue.subtract(costValue);

            v.setPrice(price);
            v.setPriceBasis(pick.label);
            v.setCostValue(costValue);
            v.setMarketValue(marketValue);
            v.setTotalProfit(profit);
            v.setTotalProfitPercent(percent(profit, costValue));
            if (q != null && !PRICE_COST.equals(pick.label)) {
                v.setQuoted(true);
                v.setPreClose(q.getPreClose());
                v.setQuoteTime(q.getTimeText());
            }

            DayBasis basis = dayBasisOf(p, today, q);
            v.setDayBuyQty(basis.dayQty);
            v.setDayBuyAvgPrice(basis.dayBuyAvg);
            v.setDayBasisPrice(basis.price);
            v.setDayBasis(basis.label);
            if (basis.price != null && basis.price.signum() > 0) {
                BigDecimal dayProfitOne = price.subtract(basis.price).multiply(qty)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                v.setDayProfit(dayProfitOne);
                v.setDayProfitPercent(percent(price.subtract(basis.price), basis.price));
                c.dayProfit = c.dayProfit.add(dayProfitOne);
                c.dayBasisValue = c.dayBasisValue.add(basis.price.multiply(qty));
            } else {
                // 基准价凑不出来（行情与当天买入池都没有）——这一条不参与当日汇总，
                // 并让账户级收益率退化成"不显示"，而不是拿一个假的分母去算
                c.dayKnown = false;
            }

            c.positionValue = c.positionValue.add(marketValue);
            c.positionCost = c.positionCost.add(costValue);
            c.positionProfit = c.positionProfit.add(profit);
            c.views.add(v);
        }
        return c;
    }

    /**
     * 一条持仓的现价取值结果：价格 + 口径说明。
     *
     * <p>口径必须跟着价格一起下发 —— 页面要能说清楚「这个市值是按昨收算的」，
     * 否则用户看到开盘前市值与昨天收盘一样、当日收益 0.00，会以为是没刷新。
     */
    private static final class PricePick {
        final BigDecimal price;
        final String label;

        PricePick(BigDecimal price, String label) {
            this.price = price;
            this.label = label;
        }
    }

    /**
     * 取一条持仓的估值价：现价 → 昨收（已在客户端回落）→ 进程内最近价 → 成本价。
     *
     * <p>只有最后一档才是成本价，而且要如实标成「成本价」—— 它是所有来源都没有时的
     * 最后兜底，不该被当成正常估值口径。
     */
    private PricePick pickPrice(Position p, Quote q, BigDecimal cost) {
        if (q != null && q.getPrice() != null && q.getPrice().signum() > 0) {
            return new PricePick(q.getPrice(), q.isPriceFromPreClose() ? PRICE_PRE_CLOSE : PRICE_LIVE);
        }
        BigDecimal last = p.getCode() == null ? null : lastPriceCache.get(p.getCode());
        if (last != null && last.signum() > 0) {
            return new PricePick(last, PRICE_LAST);
        }
        return new PricePick(cost, PRICE_COST);
    }

    /**
     * 一条持仓的当日盈亏基准价。
     *
     * <p>三类份额各有基准，混在一起时按份额加权：
     * <ol>
     *   <li>当天买入的（{@code dayBuyQty}，且池子日期就是今天）→ 当日买入均价；</li>
     *   <li>隔夜持仓的 → 昨收；</li>
     *   <li>整条都是今天建的（建仓日 = 今天，且没有当天买入池，例如页面直接配的持仓）→ 成本价。</li>
     * </ol>
     * 需要的价一个都拿不到时返回 {@code price = null}（调用侧按"当日盈亏不可知"处理）。
     */
    private static DayBasis dayBasisOf(Position p, String today, Quote q) {
        DayBasis b = new DayBasis();
        int held = p.getQuantity();
        int dayQty = dayQtyInHold(p, today);
        BigDecimal dayAvg = dayBuyAvgOf(p, today);
        if (dayQty > 0 && dayAvg == null) {
            dayQty = 0;
        }
        b.dayQty = dayQty;
        b.dayBuyAvg = dayAvg;

        BigDecimal preClose = q == null ? null : q.getPreClose();
        boolean hasPre = preClose != null && preClose.signum() > 0;

        if (dayQty >= held && dayQty > 0) {
            // 整条都是今天买的：不需要昨收，页面上也不会出现"昨收"字样
            b.price = dayAvg;
            b.label = BASIS_DAY_BUY;
            return b;
        }
        if (dayQty <= 0) {
            if (today.equals(p.getOpenDate())) {
                b.price = nz(p.getCostPrice());
                b.label = BASIS_OPEN_TODAY;
                return b;
            }
            b.price = hasPre ? preClose : null;
            b.label = BASIS_PRE_CLOSE;
            return b;
        }
        // 混着：当天买入的那部分按买入均价，其余按昨收
        if (!hasPre) {
            b.price = null;
            b.label = BASIS_MIXED;
            return b;
        }
        int overnight = held - dayQty;
        BigDecimal sum = dayAvg.multiply(BigDecimal.valueOf(dayQty))
                .add(preClose.multiply(BigDecimal.valueOf(overnight)));
        b.price = sum.divide(BigDecimal.valueOf(held), MONEY_SCALE + 2, RoundingMode.HALF_UP);
        b.label = BASIS_MIXED;
        return b;
    }

    /** 持仓里属于「当天买入池」的数量：池子日期不是今天就为 0（跨天自动作废） */
    private static int dayQtyInHold(Position p, String today) {
        if (!today.equals(p.getDayBuyDate())) {
            return 0;
        }
        return Math.max(0, Math.min(p.getDayBuyQty(), p.getQuantity()));
    }

    /** 当日买入均价 = 当天买入金额 ÷ 当天买入数量；池子异常时返回 null */
    private static BigDecimal dayBuyAvgOf(Position p, String today) {
        int n = dayQtyInHold(p, today);
        if (n <= 0) {
            return null;
        }
        BigDecimal amount = nz(p.getDayBuyAmount());
        if (amount.signum() <= 0) {
            return null;
        }
        return amount.divide(BigDecimal.valueOf(n), MONEY_SCALE + 2, RoundingMode.HALF_UP);
    }

    /**
     * 卖出份额里属于「当天买入」的数量。
     *
     * <p>勾了「卖的是当天买入的」就优先冲当天买入池；没勾就优先冲隔夜持仓，
     * 卖得比隔夜持仓还多时多出来的部分仍归到当天买入池（A股 T+1 下不该发生，
     * 但手工记账里可能出现，这里给出唯一自洽的解释）。
     */
    private static int soldFromDay(Position p, String today, int sellQty, boolean sameDay) {
        int dayInHold = dayQtyInHold(p, today);
        int overnight = p.getQuantity() - dayInHold;
        if (sameDay) {
            return Math.min(dayInHold, sellQty);
        }
        return Math.max(0, sellQty - overnight);
    }

    /**
     * 卖出这一笔的当日已实现盈亏 = (成交价 - 该份额基准价) × 数量。
     *
     * <p>隔夜份额的基准价取<b>今天的昨收</b>（与持仓浮动那部分同一口径）；
     * 昨收取不到时退化为「不产生已实现」，而不是拿成交价硬凑一个 0 值盈亏。
     */
    private static BigDecimal realizedOfSell(Position p, String today, int sellQty, BigDecimal unit,
                                             boolean sameDay, BigDecimal preClose) {
        int fromDay = soldFromDay(p, today, sellQty, sameDay);
        int fromOvernight = sellQty - fromDay;
        BigDecimal out = BigDecimal.ZERO;
        if (fromOvernight > 0 && preClose != null && preClose.signum() > 0) {
            out = out.add(unit.subtract(preClose).multiply(BigDecimal.valueOf(fromOvernight)));
        }
        if (fromDay > 0) {
            BigDecimal avg = dayBuyAvgOf(p, today);
            if (avg != null) {
                out = out.add(unit.subtract(avg).multiply(BigDecimal.valueOf(fromDay)));
            }
        }
        return out.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 卖掉的「当天买入份额」要从池子里扣掉，数量与金额按买入均价同比例减。
     * 池子扣空时连日期一起清掉，避免留下一个 0 数量的空池。
     */
    private static void reduceDayPool(Position p, String today, int fromDay) {
        int n = dayQtyInHold(p, today);
        if (n <= 0 || fromDay <= 0) {
            return;
        }
        int take = Math.min(fromDay, n);
        BigDecimal avg = dayBuyAvgOf(p, today);
        int left = n - take;
        p.setDayBuyQty(left);
        if (left <= 0) {
            p.setDayBuyAmount(BigDecimal.ZERO);
            p.setDayBuyDate(null);
            return;
        }
        if (avg != null) {
            BigDecimal rest = nz(p.getDayBuyAmount()).subtract(avg.multiply(BigDecimal.valueOf(take)));
            p.setDayBuyAmount(rest.signum() > 0 ? rest : BigDecimal.ZERO);
        }
    }

    /** 跨天时把「当天买入池」清掉并改挂到今天（同一天内多次买入不清） */
    private static void rolloverDayBuy(Position p, String today) {
        if (!today.equals(p.getDayBuyDate())) {
            p.setDayBuyDate(today);
            p.setDayBuyQty(0);
            p.setDayBuyAmount(BigDecimal.ZERO);
        }
    }

    /** 该标的今天的昨收：取不到返回 null（页面与已实现计算都按"不可知"处理） */
    private BigDecimal preCloseOf(String code) {
        SinaQuoteClient client = quoteClientProvider.getIfAvailable();
        if (client == null) {
            return null;
        }
        try {
            // 用估值口径取：未开盘时现价字段是 0，但昨收仍是有效的比较基准
            Quote q = client.fetchForValuation(new WatchTarget(code, code));
            return q == null ? null : q.getPreClose();
        } catch (Exception e) {
            log.warn("[account] 取昨收失败 {}: {}", code, e.getMessage());
            return null;
        }
    }

    /** 账户的当日已实现盈亏：落盘日期不是今天就作废（不需要定时任务归零） */
    private static BigDecimal dayRealizedOf(Account a, String today) {
        if (a == null || !today.equals(a.getDayRealizedDate())) {
            return BigDecimal.ZERO;
        }
        return nz(a.getDayRealizedProfit());
    }

    /** 今天的 yyyy-MM-dd */
    private static String todayYmd() {
        return LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    }

    /**
     * 一条持仓的<b>可用数量</b>（可卖出的股数，需求 v1.5.6 第 ① 条）。
     *
     * <p>三个口径一次收敛到这里，页面与后端判断卖出上限都用它：
     * <ul>
     *   <li>没单独设过（{@code null}，v1.5.6 之前的老数据）→ 持仓数量，全部可卖；</li>
     *   <li>设过 → 取它，并夹到 {@code [0, 持仓数量]}（库里被手工改脏也不至于卖出不存在的量）；</li>
     *   <li>持仓数量本身 ≤ 0 → 0（没有可卖的）。</li>
     * </ul>
     */
    static int usableQty(Position p) {
        if (p == null || p.getQuantity() <= 0) {
            return 0;
        }
        Integer raw = p.getAvailableQty();
        if (raw == null) {
            return p.getQuantity();
        }
        return Math.max(0, Math.min(raw, p.getQuantity()));
    }

    /**
     * 成交日期：空 → 今天；非法 → 报错（需求 v1.5.6 第 ② 条，默认当天）。
     *
     * <p>与建仓日同一套宽松解析（{@code yyyy-MM-dd} / {@code yyyyMMdd} / {@code yyyy/MM/dd} /
     * {@code yyyy.MM.dd} 都认）；不接受未来日期 —— 成交不可能发生在明天，填错了直接拦。
     */
    private static String checkTradeDate(String raw, String today) {
        String ymd = checkOpenDate(raw);
        if (ymd == null) {
            return today;
        }
        if (ymd.compareTo(today) > 0) {
            throw new IllegalArgumentException("成交日期不能晚于今天");
        }
        return ymd;
    }

    /** 建仓日宽松解析：空 → null；非法 → 直接报错让用户改 */
    private static String checkOpenDate(String raw) {
        return checkYmd(raw, "建仓日");
    }

    /**
     * yyyy-MM-dd 宽松解析（建仓日 / 成交日期 / 导入文件里的日期都用它）：
     * 空 → null；非法 → 报错让用户改。
     *
     * <p>{@code yyyy-MM-dd} / {@code yyyyMMdd} / {@code yyyy/MM/dd} / {@code yyyy.MM.dd} 都认，
     * 但年月日必须真实存在（2026-02-30 直接拒）。
     */
    static String checkYmd(String raw, String label) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String digits = s.replace("-", "").replace("/", "").replace(".", "");
        if (digits.length() != 8 || !digits.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException(label + "格式不对，请按 2026-09-16 这样的日期填写");
        }
        try {
            return LocalDate.parse(digits, YMD_COMPACT).format(DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            throw new IllegalArgumentException(label + "不是有效日期，请确认年月日");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
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

    /**
     * 批量取现价：<b>走估值口径</b>（未开盘时现价回落到昨收），逐只取，
     * 取不到的标的直接缺省（调用侧按 {@link #pickPrice} 的兜底链处理）。
     *
     * <p>取到有效现价就顺手塞进 {@link #lastPriceCache}：下一轮某只标的请求失败时，
     * 用它顶上，市值不会因为一次超时跳到成本价。
     */
    private Map<String, Quote> fetchQuotes(Map<String, String> wanted) {
        Map<String, Quote> out = new LinkedHashMap<>();
        SinaQuoteClient client = quoteClientProvider.getIfAvailable();
        if (client == null || wanted.isEmpty()) {
            return out;
        }
        for (Map.Entry<String, String> e : wanted.entrySet()) {
            try {
                Quote q = client.fetchForValuation(new WatchTarget(e.getValue(), e.getKey()));
                if (q != null) {
                    out.put(e.getKey(), q);
                    if (q.getPrice() != null && q.getPrice().signum() > 0 && !q.isPriceFromPreClose()) {
                        lastPriceCache.put(e.getKey(), q.getPrice());
                    }
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
    static String normalizeCode(String code) {
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
