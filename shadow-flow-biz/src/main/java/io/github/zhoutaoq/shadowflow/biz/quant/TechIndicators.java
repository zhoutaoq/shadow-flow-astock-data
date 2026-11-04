package io.github.zhoutaoq.shadowflow.biz.quant;

import java.util.List;

/**
 * 技术指标计算：MACD / KDJ / RSI / BOLL / ATR / 均线 / 52 周高低。
 *
 * <p>全部本地算，不引第三方指标库：一是项目只依赖 JDK + Jackson，多一个库不值；
 * 二是这些公式的差异全在细节（EMA 起点、RSV 分母为 0、TR 的三种取法），
 * 自己写能保证和主流行情软件同一口径，出问题也查得到。
 *
 * <p>输入必须是<b>按交易日升序</b>的日K，且至少 30 根；不足时指标返回 0，
 * 由调用方决定是降权还是不用（不要用 0 当真实值参与打分）。
 */
public final class TechIndicators {

    private TechIndicators() {
    }

    /** 指标结果。数值为 0 表示「算不出来」，调用方需自己判断 */
    public static class TechSnapshot {
        public int bars;
        public double ma5;
        public double ma10;
        public double ma20;
        public double ma60;
        /** MACD 的 DIF 线 */
        public double dif;
        /** MACD 的 DEA 线 */
        public double dea;
        /** MACD 柱 =(DIF - DEA) × 2，正数红柱、负数绿柱 */
        public double macdBar;
        /** 上一根的柱值，用来判断红柱在放大还是缩短 */
        public double prevMacdBar;
        public double k;
        public double d;
        public double j;
        /** 上一根的 K/D，用来判断金叉死叉 */
        public double prevK;
        public double prevD;
        public double rsi6;
        public double bollUpper;
        public double bollMid;
        public double bollLower;
        /** 14 日真实波幅均值：止损宽度用它，不用固定百分比 */
        public double atr;
        /** 近 250 个交易日（约 52 周）最高 / 最低 */
        public double high52w;
        public double low52w;
        /** 近 60 日最高价（前高），突破判断用 */
        public double prevHigh60;
        /** 近 60 日最低价 */
        public double prevLow60;
    }

    /**
     * 一次算全。
     *
     * @param bars 升序日K，至少 30 根
     */
    public static TechSnapshot compute(List<EastmoneyClient.DailyBar> bars) {
        TechSnapshot t = new TechSnapshot();
        if (bars == null || bars.size() < 30) {
            return t;
        }
        int n = bars.size();
        t.bars = n;

        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = d(bars.get(i).close);
            high[i] = d(bars.get(i).high);
            low[i] = d(bars.get(i).low);
        }

        t.ma5 = ma(close, 5, n - 1);
        t.ma10 = ma(close, 10, n - 1);
        t.ma20 = ma(close, 20, n - 1);
        t.ma60 = ma(close, 60, n - 1);

        // MACD(12, 26, 9)
        double[] ema12 = ema(close, 12);
        double[] ema26 = ema(close, 26);
        double[] dif = new double[n];
        for (int i = 0; i < n; i++) {
            dif[i] = ema12[i] - ema26[i];
        }
        double[] dea = ema(dif, 9);
        t.dif = dif[n - 1];
        t.dea = dea[n - 1];
        t.macdBar = (t.dif - t.dea) * 2;
        t.prevMacdBar = (dif[n - 2] - dea[n - 2]) * 2;

        // KDJ(9, 3, 3)：K/D 从 50 起步，和主流软件一致
        double k = 50, dd = 50, prevK = 50, prevD = 50;
        for (int i = 8; i < n; i++) {
            double hh = high[i], ll = low[i];
            for (int j = i - 8; j <= i; j++) {
                hh = Math.max(hh, high[j]);
                ll = Math.min(ll, low[j]);
            }
            double rsv = hh == ll ? 50 : (close[i] - ll) / (hh - ll) * 100;
            prevK = k;
            prevD = dd;
            k = k * 2 / 3 + rsv / 3;
            dd = dd * 2 / 3 + k / 3;
        }
        t.k = k;
        t.d = dd;
        t.j = 3 * k - 2 * dd;
        t.prevK = prevK;
        t.prevD = prevD;

        t.rsi6 = rsi(close, 6);

        // BOLL(20, 2)
        double mid = ma(close, 20, n - 1);
        double sum = 0;
        for (int i = n - 20; i < n; i++) {
            sum += Math.pow(close[i] - mid, 2);
        }
        double std = Math.sqrt(sum / 20);
        t.bollMid = mid;
        t.bollUpper = mid + 2 * std;
        t.bollLower = mid - 2 * std;

        t.atr = atr(high, low, close, 14);

        double hi = close[n - 1], lo = close[n - 1];
        for (int i = Math.max(0, n - 250); i < n; i++) {
            hi = Math.max(hi, high[i]);
            lo = Math.min(lo, low[i]);
        }
        t.high52w = hi;
        t.low52w = lo;

        double hi60 = high[n - 1], lo60 = low[n - 1];
        for (int i = Math.max(0, n - 60); i < n; i++) {
            hi60 = Math.max(hi60, high[i]);
            lo60 = Math.min(lo60, low[i]);
        }
        t.prevHigh60 = hi60;
        t.prevLow60 = lo60;
        return t;
    }

    /** 简单均线：取最后 end 往前 period 根的算术平均 */
    private static double ma(double[] v, int period, int end) {
        if (end + 1 < period) {
            return 0;
        }
        double sum = 0;
        for (int i = end - period + 1; i <= end; i++) {
            sum += v[i];
        }
        return sum / period;
    }

    /** 指数均线：首值用第一根收盘价起步 */
    private static double[] ema(double[] v, int period) {
        double[] out = new double[v.length];
        double alpha = 2.0 / (period + 1);
        out[0] = v[0];
        for (int i = 1; i < v.length; i++) {
            out[i] = alpha * v[i] + (1 - alpha) * out[i - 1];
        }
        return out;
    }

    /** RSI：用简单平均涨跌幅（与通达信口径一致），不是 Wilder 平滑 */
    private static double rsi(double[] close, int period) {
        int n = close.length;
        if (n <= period) {
            return 0;
        }
        double gain = 0, loss = 0;
        for (int i = n - period; i < n; i++) {
            double chg = close[i] - close[i - 1];
            if (chg >= 0) {
                gain += chg;
            } else {
                loss -= chg;
            }
        }
        if (gain + loss == 0) {
            return 50;
        }
        return gain / (gain + loss) * 100;
    }

    /**
     * ATR(14)：TR 取「高-低、|高-昨收|、|低-昨收|」三者最大。
     *
     * <p>停牌、一字板时前两项会退化，第三项兜住，这也是主流算法都这么写的原因。
     */
    private static double atr(double[] high, double[] low, double[] close, int period) {
        int n = close.length;
        if (n <= period) {
            return 0;
        }
        double sum = 0;
        for (int i = n - period; i < n; i++) {
            double prevClose = close[i - 1];
            double tr = Math.max(high[i] - low[i],
                    Math.max(Math.abs(high[i] - prevClose), Math.abs(low[i] - prevClose)));
            sum += tr;
        }
        return sum / period;
    }

    private static double d(java.math.BigDecimal v) {
        return v == null ? 0 : v.doubleValue();
    }
}
