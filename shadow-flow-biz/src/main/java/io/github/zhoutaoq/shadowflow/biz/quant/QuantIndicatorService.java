package io.github.zhoutaoq.shadowflow.biz.quant;

import io.github.zhoutaoq.shadowflow.biz.market.MarketIndex;
import io.github.zhoutaoq.shadowflow.biz.market.MarketIndexService;
import io.github.zhoutaoq.shadowflow.biz.market.RealtimeBoardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 指标计算：把一只标的的原始行情换算成十二项 0-100 分，并给出三层判定。
 *
 * <p><b>打分原则</b>（这几条决定了打分不会变成玄学）：
 * <ol>
 *   <li>每项都是「围绕 50 分做加减」，不是从 0 加起。取不到数据时留在 50（中性），
 *       绝不因为一次网络失败就把一只票打成 0 分；</li>
 *   <li>扣分比加分更狠。做量化最容易犯的错是「指标全绿就买」，
 *       而真实亏损更多来自「明显该躲的时候没躲」，所以危险信号一律给大权重；</li>
 *   <li>L1 三项不参与加权否决 —— 它们各自有硬条件，任一不满足直接 mark 失败。</li>
 * </ol>
 *
 * <p>大盘环境 60 秒缓存一次：它对所有标的是同一个值，每只都重算既慢又会出现
 * 「同一轮里两个标的看到两个大盘」的怪象。
 */
@Service
public class QuantIndicatorService {

    private static final Logger log = LoggerFactory.getLogger(QuantIndicatorService.class);

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final long MARKET_TTL_MS = 60_000L;

    private final EastmoneyClient em;
    private final MarketIndexService indexService;
    private final RealtimeBoardService boardService;

    private volatile MarketContext marketCache;
    private volatile long marketAt;

    public QuantIndicatorService(EastmoneyClient em, MarketIndexService indexService,
                                 RealtimeBoardService boardService) {
        this.em = em;
        this.indexService = indexService;
        this.boardService = boardService;
    }

    /* ==================== 大盘环境 ==================== */

    /**
     * 大盘环境（所有标的共用）。
     *
     * <p>闸门条件按需求原话：「上证在 52 周低上方 &lt; 2% 一律轻仓」。
     * 再加一条环境分下限 40 —— 环境分本身是多项合计，只卡一项会漏掉
     * 「指数没跌但个股普跌」这种更常见的坏天气。
     */
    public MarketContext market() {
        long now = System.currentTimeMillis();
        MarketContext hit = marketCache;
        if (hit != null && now - marketAt < MARKET_TTL_MS) {
            return hit;
        }
        MarketContext ctx = new MarketContext();
        List<String> notes = new ArrayList<>();
        int score = 50;

        BigDecimal shPct = BigDecimal.ZERO;
        BigDecimal szPct = BigDecimal.ZERO;
        BigDecimal cybPct = BigDecimal.ZERO;
        try {
            for (MarketIndex idx : indexService.indices()) {
                if (idx.getCode() == null) {
                    continue;
                }
                String code = idx.getCode().toLowerCase();
                BigDecimal pct = idx.getChangePercent() == null ? BigDecimal.ZERO : idx.getChangePercent();
                if ("sh000001".equals(code)) {
                    shPct = pct;
                } else if ("sz399001".equals(code)) {
                    szPct = pct;
                } else if ("sz399006".equals(code)) {
                    cybPct = pct;
                }
            }
        } catch (RuntimeException e) {
            notes.add("指数取数失败：" + e.getMessage());
        }
        ctx.setShChangePct(shPct);
        ctx.setSzChangePct(szPct);
        ctx.setCybChangePct(cybPct);

        // 上证距 52 周低：这一项是关键，取不到就把分数压到中性并说明
        BigDecimal distance = BigDecimal.ZERO;
        List<EastmoneyClient.DailyBar> shBars = em.indexBars("1.000001");
        if (!shBars.isEmpty()) {
            BigDecimal low = null;
            for (EastmoneyClient.DailyBar b : shBars) {
                if (b.low != null && (low == null || b.low.compareTo(low) < 0)) {
                    low = b.low;
                }
            }
            BigDecimal point = shBars.get(shBars.size() - 1).close;
            if (low != null && low.signum() > 0 && point != null) {
                distance = point.subtract(low).multiply(HUNDRED).divide(low, 2, BigDecimal.ROUND_HALF_UP);
            }
        }
        ctx.setShDistanceToLowPct(distance);

        // 涨跌家数与涨跌停：失败不影响主流程
        int up = 0, down = 0, limitUp = 0, limitDown = 0;
        try {
            Map<String, Object> snapshot = boardService.snapshot();
            Object statsObj = snapshot.get("stats");
            if (statsObj instanceof Map) {
                Map<?, ?> stats = (Map<?, ?>) statsObj;
                up = intOf(stats.get("up"));
                down = intOf(stats.get("down"));
                limitUp = intOf(stats.get("limitUp"));
                limitDown = intOf(stats.get("limitDown"));
            }
        } catch (RuntimeException e) {
            notes.add("涨跌家数取数失败：" + e.getMessage());
        }
        ctx.setUpCount(up);
        ctx.setDownCount(down);
        ctx.setLimitUpCount(limitUp);
        ctx.setLimitDownCount(limitDown);

        // 板块资金流宽度：多少行业是净流入
        int inflow = 0, total = 0;
        try {
            for (EastmoneyClient.SectorFlow s : em.sectorFlows()) {
                if (s.mainNetInflow == null) {
                    continue;
                }
                total++;
                if (s.mainNetInflow.signum() > 0) {
                    inflow++;
                }
            }
        } catch (RuntimeException e) {
            notes.add("板块资金流取数失败：" + e.getMessage());
        }
        ctx.setSectorInflowCount(inflow);
        ctx.setSectorTotal(total);

        /* ---------- 打分 ---------- */

        // 指数涨跌：1% 折 10 分，单边最多 ±20
        score += clampInt(shPct.doubleValue() * 10, -20, 20);
        score += clampInt(cybPct.doubleValue() * 10, -20, 20) / 2;

        if (distance.signum() != 0) {
            double d = distance.doubleValue();
            if (d >= 10) {
                score += 10;
            } else if (d >= 5) {
                score += 5;
            } else if (d >= 2) {
                score -= 5;
            } else {
                score -= 20;
                notes.add("上证距 52 周低 " + distance.toPlainString() + "%，跌破安全距离");
            }
        } else {
            notes.add("52 周低点取不到，位置项按中性处理");
        }

        double breadth = (up + down) > 0 ? (double) up / (up + down) : 0.5;
        if (breadth >= 0.6) {
            score += 10;
        } else if (breadth < 0.4) {
            score -= 10;
        }
        if (limitUp + limitDown > 0) {
            if (limitUp >= limitDown * 3) {
                score += 5;
            } else if (limitDown > limitUp) {
                score -= 10;
            }
        }
        if (total > 0) {
            double r = (double) inflow / total;
            if (r >= 0.5) {
                score += 5;
            } else if (r < 0.3) {
                score -= 5;
            }
        }

        score = clamp(score);
        ctx.setScore(score);
        // 只在「明确算出距 52 周低 < 2%」时否决。取不到（distance = 0）不能当成跌破 ——
        // 东财历史接口会间歇性被风控挡掉，把「没数据」判成「坏天气」等于每天随机禁止交易
        boolean nearLow = distance.signum() > 0 && distance.doubleValue() < 2;
        ctx.setPass(score >= 40 && !nearLow);
        notes.add(0, "环境分 " + score + "（上证 " + shPct.toPlainString() + "%，上涨 "
                + up + " 家 / 下跌 " + down + " 家，净流入板块 " + inflow + "/" + total + "）");
        ctx.setNotes(notes);

        marketCache = ctx;
        marketAt = now;
        return ctx;
    }

    /* ==================== 个股指标 ==================== */

    /**
     * 算一只标的的十二项指标与三层判定。
     *
     * @param q    批量快照（必须非空）
     * @param flow 资金流，可为 null（按中性处理）
     * @param bars 日K，可为空（技术指标按中性处理）
     */
    public QuantMetrics evaluate(QuantQuote q, EastmoneyClient.MainFlow flow,
                                 List<EastmoneyClient.DailyBar> bars, MarketContext market) {
        QuantMetrics m = new QuantMetrics();
        m.setCode(q.getCode());
        m.setName(q.getName());
        m.setPrice(q.getPrice());
        m.setChangePct(q.getChangePct());

        TechIndicators.TechSnapshot tech = TechIndicators.compute(bars);

        /* ---------- L1-① 大盘（20%） ---------- */
        m.setMarketScore(market == null ? 50 : market.getScore());

        /* ---------- L1-② 主力资金（18%） ---------- */
        BigDecimal todayMain = BigDecimal.ZERO;
        BigDecimal main5 = BigDecimal.ZERO;
        BigDecimal main10 = BigDecimal.ZERO;
        BigDecimal main20 = BigDecimal.ZERO;
        BigDecimal jumbo = BigDecimal.ZERO;
        if (flow != null) {
            todayMain = flow.todayMain();
            jumbo = flow.todayJumbo();
            main5 = flow.sumMain(5);
            main10 = flow.sumMain(10);
            main20 = flow.sumMain(20);
        } else {
            todayMain = q.getMainNetInflow();
            m.getNotes().add("资金流历史取不到，5/10/20 日按中性处理");
        }
        m.setTodayMain(todayMain);
        m.setMain5d(main5);
        m.setMain10d(main10);
        m.setMain20d(main20);
        m.setTodayJumbo(jumbo);
        m.setMainScore(scoreMain(todayMain, main5, main20, jumbo, q.getAmount()));

        /* ---------- L1-③ 位置（15%） ---------- */
        BigDecimal d52h = BigDecimal.ZERO;
        BigDecimal d52l = BigDecimal.ZERO;
        BigDecimal dPrev = BigDecimal.ZERO;
        if (tech.bars >= 30 && q.getPrice().signum() > 0) {
            // 「距高点」一律取绝对值：现价永远 ≤ 52 周高，不取绝对值就是负数，
            // 后面拿它和 8/30 这类阈值比大小会全部走错分支
            d52h = pct(q.getPrice(), tech.high52w).abs();
            d52l = pct(q.getPrice(), tech.low52w);
            dPrev = pct(q.getPrice(), tech.prevHigh60).abs();
        }
        m.setDistanceTo52wHighPct(d52h);
        m.setDistanceTo52wLowPct(d52l);
        m.setDistanceToPrevHighPct(dPrev);
        m.setPositionScore(scorePosition(d52h, d52l, dPrev, tech.bars));

        /* ---------- L2-① 量能（10%） ---------- */
        m.setVolumeRatio(q.getVolumeRatio());
        // 分时量能：这一分钟相对前 5 分钟是放量还是缩量
        m.setVolSurge(em.minuteVolumeSurge(q.getCode(), 5));
        m.setTurnoverRate(q.getTurnoverRate());
        m.setAmount(q.getAmount());
        m.setVolumeScore(scoreVolume(q.getVolumeRatio(), q.getTurnoverRate(), tech.bars));

        /* ---------- L2-② 均价线（8%） ---------- */
        m.setAvgPrice(q.getAvgPrice());
        m.setAvgScore(scoreAvgLine(q));

        /* ---------- L2-③ 委比 + 内外盘（7%） ---------- */
        m.setWbRatio(q.getWbRatio());
        m.setOuterInnerDiff(q.outerMinusInner());
        m.setOrderScore(scoreOrder(q.getWbRatio(), q.outerMinusInner()));

        /* ---------- L3 形态（6%） ---------- */
        m.setPatternScore(scorePattern(q, bars));

        /* ---------- L3 技术指标（5%） ---------- */
        m.setMacdBar(BigDecimal.valueOf(tech.macdBar).setScale(3, BigDecimal.ROUND_HALF_UP));
        m.setAtr(BigDecimal.valueOf(tech.atr).setScale(3, BigDecimal.ROUND_HALF_UP));
        m.setTechScore(scoreTech(q, tech));

        /* ---------- L3 区间涨跌（5%） ---------- */
        BigDecimal c5 = chgFrom(bars, q.getPrice(), 5);
        BigDecimal c10 = chgFrom(bars, q.getPrice(), 10);
        BigDecimal c20 = chgFrom(bars, q.getPrice(), 20);
        BigDecimal c60 = chgFrom(bars, q.getPrice(), 60);
        m.setChg5d(c5);
        m.setChg10d(c10);
        m.setChg20d(c20);
        m.setChg60d(c60);
        m.setRangeScore(scoreRange(c5, c20, c60));

        /* ---------- L3 基本面（3%）/ 机构预期（2%）：按日缓存，取不到按中性 ---------- */
        Integer fund = em.financialScore(q.getCode());
        m.setFundamentalScore(fund == null ? 50 : fund);
        Integer est = em.estimateScore(q.getCode());
        m.setEstimateScore(est == null ? 50 : est);
        if (fund == null && est == null) {
            m.getNotes().add("基本面与一致预期均取不到，按中性处理");
        }

        /* ---------- 第一层硬闸 ---------- */
        List<String> fails = new ArrayList<>();
        if (market != null && !market.isPass()) {
            fails.add("大盘环境不过（" + market.getScore() + " 分）");
        }
        if (todayMain.signum() <= 0) {
            fails.add("今日主力净流出 " + money(todayMain));
        }
        if (main5.signum() <= 0 && main20.signum() <= 0) {
            fails.add("5 日与 20 日主力均为净流出");
        }
        if (tech.bars >= 30) {
            if (d52h.doubleValue() < 8) {
                fails.add("距 52 周高仅 " + d52h.toPlainString() + "%，位置过高");
            }
            if (d52l.doubleValue() < 3) {
                fails.add("距 52 周低仅 " + d52l.toPlainString() + "%，仍在下跌趋势");
            }
        }
        m.setL1Fails(fails);
        m.setL1Pass(fails.isEmpty());

        /* ---------- 第二层分值 ---------- */
        m.setL2Score((m.getVolumeScore() * 10 + m.getAvgScore() * 8 + m.getOrderScore() * 7) / 25);

        /* ---------- 自建评分（1%）：前十一项的简单平均，不额外引入主观项 ---------- */
        m.setScoreSystem((m.getMarketScore() + m.getMainScore() + m.getPositionScore()
                + m.getVolumeScore() + m.getAvgScore() + m.getOrderScore()
                + m.getPatternScore() + m.getTechScore() + m.getRangeScore()) / 9);

        m.computeTotal();

        /* ---------- 止损价：2×ATR，夹在 -10% ~ -3% ---------- */
        m.setStopPrice(stopPriceOf(q.getPrice(), tech.atr));
        return m;
    }

    /* ==================== 各分项打分 ==================== */

    /**
     * 主力资金：占比比绝对值更能说明问题 —— 同样是净流入 1 亿，
     * 在 20 亿成交额的小票里是强控盘，在 500 亿的权重股里什么都不是。
     */
    private int scoreMain(BigDecimal today, BigDecimal main5, BigDecimal main20,
                          BigDecimal jumbo, BigDecimal amount) {
        int s = 50;
        if (amount != null && amount.signum() > 0) {
            double ratio = today.doubleValue() / amount.doubleValue() * 100;
            if (ratio >= 5) {
                s += 30;
            } else if (ratio >= 2) {
                s += 20;
            } else if (ratio > 0) {
                s += 10;
            } else if (ratio > -2) {
                s -= 5;
            } else {
                s -= 20;
            }
        } else {
            s += today.signum() > 0 ? 10 : -10;
        }
        if (main5.signum() > 0) {
            s += 10;
        } else {
            s -= 10;
        }
        if (main20.signum() > 0) {
            s += 5;
        } else {
            s -= 15;
        }
        // 特大单：验证「真买还是假拉」的最后一道
        s += jumbo.signum() > 0 ? 10 : -10;
        return clamp(s);
    }

    /**
     * 位置：赔率的来源。
     *
     * <p>「距 52 周高近」不一定是坏事（突破），「距 52 周低近」也不一定是好事（下跌趋势），
     * 所以两头都扣分，中段（回调 10%-30%）给最高分 —— 那才是赔率最好的区域。
     */
    private int scorePosition(BigDecimal dHigh, BigDecimal dLow, BigDecimal dPrev, int bars) {
        if (bars < 30) {
            return 50;
        }
        int s = 50;
        double h = dHigh.doubleValue();
        if (h > 30) {
            s += 20;          // 深度回调，空间大
        } else if (h > 15) {
            s += 10;
        } else if (h > 8) {
            s += 0;
        } else if (h > 3) {
            s -= 10;          // 逼近前高，压力区
        } else {
            s -= 20;          // 贴着历史高位
        }

        double l = dLow.doubleValue();
        if (l < 5) {
            s -= 20;          // 贴 52 周低，趋势向下
        } else if (l < 15) {
            s -= 10;
        } else if (l < 40) {
            s += 5;
        } else {
            s += 10;
        }

        // 距 60 日前高 < 2%：在突破位上，量能配合就是买点，不配合就是诱多
        if (dPrev.abs().doubleValue() < 2) {
            s += 5;
        }
        return clamp(s);
    }

    /** 量能：量比是「今天有没有人」的第一指标；换手过高反而是出货 */
    private int scoreVolume(BigDecimal ratio, BigDecimal turnover, int bars) {
        if (bars < 30) {
            return 50;
        }
        int s = 50;
        double r = ratio == null ? 0 : ratio.doubleValue();
        if (r >= 2.0) {
            s += 25;
        } else if (r >= 1.5) {
            s += 20;
        } else if (r >= 1.0) {
            s += 10;
        } else if (r >= 0.8) {
            s += 0;
        } else {
            s -= 15;
        }
        double t = turnover == null ? 0 : turnover.doubleValue();
        if (t >= 1 && t <= 5) {
            s += 10;
        } else if (t > 15) {
            s -= 10;
        } else if (t > 10) {
            s -= 5;
        }
        return clamp(s);
    }

    /**
     * 均价线：白线在黄线上方 = 日内多头控盘。
     *
     * <p>这条只对「当天」有效，隔夜毫无意义，所以不参与任何跨日比较。
     */
    private int scoreAvgLine(QuantQuote q) {
        BigDecimal diff = q.priceVsAvgPct();
        double d = diff.doubleValue();
        int s;
        if (d >= 1.5) {
            s = 80;
        } else if (d >= 0.5) {
            s = 65;
        } else if (d >= 0) {
            s = 55;
        } else if (d >= -1) {
            s = 40;
        } else if (d >= -2) {
            s = 25;
        } else {
            s = 10;
        }
        return s;
    }

    /**
     * 委比 + 内外盘：两项合计 7%，且<b>最多只能加减 10 分</b>。
     *
     * <p>原因是实测过的：同一只票 30 分钟内委比从 +7.67 跳到 -49.53 再到 +29.94，
     * 价格几乎没动。挂单能撤，所以这一项永远只能做「微调」，不能做「依据」。
     */
    private int scoreOrder(BigDecimal wb, BigDecimal outerInner) {
        int s = 50;
        if (wb != null) {
            double w = wb.doubleValue();
            if (w >= 20) {
                s += 6;
            } else if (w >= 0) {
                s += 3;
            } else if (w > -20) {
                s -= 3;
            } else {
                s -= 6;
            }
        }
        if (outerInner != null) {
            s += outerInner.signum() > 0 ? 4 : -4;
        }
        return clamp(s);
    }

    /**
     * 形态：长上影 / 冲高回落是最常见的「看起来涨了其实在出货」。
     *
     * <p>用「(最高-收盘)/(最高-最低)」量化上影线占比，> 0.6 且当天冲高 2% 以上才扣分 ——
     * 一字板、窄幅横盘时这个分母很小，不加前置条件会把正常波动误判成出货。
     */
    private int scorePattern(QuantQuote q, List<EastmoneyClient.DailyBar> bars) {
        int s = 50;
        BigDecimal high = q.getHigh();
        BigDecimal low = q.getLow();
        BigDecimal price = q.getPrice();
        BigDecimal preClose = q.getPreClose();
        if (high != null && low != null && high.subtract(low).signum() > 0 && price != null) {
            double shadow = high.subtract(price).doubleValue() / high.subtract(low).doubleValue();
            double intradayRise = preClose != null && preClose.signum() > 0
                    ? high.subtract(preClose).doubleValue() / preClose.doubleValue() * 100 : 0;
            if (shadow > 0.6 && intradayRise > 2) {
                s -= 25;
            } else if (shadow < 0.2) {
                s += 15;
            }
        }
        if (bars != null && bars.size() >= 4) {
            int downDays = 0;
            for (int i = bars.size() - 3; i < bars.size(); i++) {
                EastmoneyClient.DailyBar b = bars.get(i);
                if (b.changePct != null && b.changePct.signum() < 0) {
                    downDays++;
                }
            }
            if (downDays == 3) {
                s -= 15;
            }
        }
        return clamp(s);
    }

    /** 技术指标：只做验证，不单独构成买入依据 —— 所以分差给得比 L1/L2 小 */
    private int scoreTech(QuantQuote q, TechIndicators.TechSnapshot t) {
        if (t.bars < 30) {
            return 50;
        }
        int s = 50;
        if (t.dif > t.dea) {
            s += 8;
        } else {
            s -= 8;
        }
        if (t.macdBar > 0 && t.macdBar > t.prevMacdBar) {
            s += 8;      // 红柱放大
        } else if (t.macdBar > 0) {
            s -= 5;      // 红柱缩短
        }
        if (t.k > t.d && t.prevK <= t.prevD) {
            s += 8;      // 金叉
        } else if (t.k < t.d && t.prevK >= t.prevD) {
            s -= 8;      // 死叉
        }
        if (t.j > 100) {
            s -= 8;      // 超买
        } else if (t.j < 0) {
            s += 3;
        }
        if (t.rsi6 > 80) {
            s -= 8;
        } else if (t.rsi6 >= 40 && t.rsi6 <= 70) {
            s += 5;
        }
        if (t.ma20 > 0 && q.getPrice() != null) {
            s += q.getPrice().doubleValue() > t.ma20 ? 8 : -8;
        }
        return clamp(s);
    }

    /** 区间涨跌：判断处在趋势的哪个阶段，涨太多和跌太多都扣分 */
    private int scoreRange(BigDecimal c5, BigDecimal c20, BigDecimal c60) {
        int s = 50;
        double d5 = c5.doubleValue();
        if (d5 >= 0 && d5 <= 5) {
            s += 8;
        } else if (d5 > 10) {
            s -= 10;
        } else if (d5 < -8) {
            s -= 8;
        }
        double d20 = c20.doubleValue();
        if (d20 >= 0 && d20 <= 20) {
            s += 10;
        } else if (d20 > 40) {
            s -= 15;
        } else if (d20 < -15) {
            s -= 10;
        }
        double d60 = c60.doubleValue();
        if (d60 > 80) {
            s -= 10;
        } else if (d60 < -30) {
            s -= 5;
        }
        return clamp(s);
    }

    /* ==================== 工具 ==================== */

    /** 止损价 = 现价 - 2×ATR，并夹在成本价下方 3% ~ 10% 的合理区间内 */
    private BigDecimal stopPriceOf(BigDecimal price, double atr) {
        if (price == null || price.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal byAtr = atr > 0
                ? price.subtract(BigDecimal.valueOf(atr * 2))
                : price.multiply(BigDecimal.valueOf(0.95));
        BigDecimal floor = price.multiply(BigDecimal.valueOf(0.90));
        BigDecimal ceil = price.multiply(BigDecimal.valueOf(0.97));
        if (byAtr.compareTo(floor) < 0) {
            return floor.setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        if (byAtr.compareTo(ceil) > 0) {
            return ceil.setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        return byAtr.setScale(2, BigDecimal.ROUND_HALF_UP);
    }

    /** 现价相对 N 个交易日前的涨跌幅（%） */
    private static BigDecimal chgFrom(List<EastmoneyClient.DailyBar> bars, BigDecimal price, int days) {
        if (bars == null || bars.size() <= days || price == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = bars.get(bars.size() - 1 - days).close;
        if (base == null || base.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return price.subtract(base).multiply(HUNDRED).divide(base, 2, BigDecimal.ROUND_HALF_UP);
    }

    private static BigDecimal pct(BigDecimal price, double base) {
        if (base <= 0 || price == null) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(price.doubleValue() - base)
                .multiply(HUNDRED)
                .divide(BigDecimal.valueOf(base), 2, BigDecimal.ROUND_HALF_UP);
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(100, v));
    }

    private static int clampInt(double v, int min, int max) {
        return (int) Math.max(min, Math.min(max, Math.round(v)));
    }

    private static int intOf(Object v) {
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        return 0;
    }

    private static String money(BigDecimal v) {
        if (v == null) {
            return "0";
        }
        BigDecimal wan = v.divide(BigDecimal.valueOf(10000), 2, BigDecimal.ROUND_HALF_UP);
        return wan.toPlainString() + " 万";
    }
}
