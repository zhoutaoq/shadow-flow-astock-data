package com.stock.kline.watch;

import java.util.ArrayList;
import java.util.List;

/**
 * 盯盘的一份完整配置（页面提交与落盘都用它）。
 *
 * <p>页面改完配置后：先更新内存里的这一份，再整体落盘成 JSON；
 * 进程启动时如果落盘文件不存在，就用 yml 里的值作为默认值，
 * 并且尝试把老的 watch-list.txt 名单迁移过来，避免升级后名单丢失。
 *
 * <p><b>v1.3</b>：阈值改成多条规则（过去时长 + 偏离百分比），每条独立触发。
 * 时间长度按"轮询间隔的倍数"计量，把"轮询间隔 60s、过去 3 个周期 = 180 秒"这种语义化，
 * 页面上就不用再独立维护一个时长字段。
 *
 * <p><b>v1.5.9</b>：钉钉机器人移出本配置 —— 它是系统对接第三方的设施，
 * 全站只保留系统配置页维护的那一只，不再随人落盘。
 *
 * <p>涨跌停的"放量"判断阈值（{@link SurgeCfg}）同样放在这份配置里，页面可改、落盘持久化。
 */
public class WatchSettings {

    /** 盯盘总开关：关掉后调度直接停，不再查行情 */
    private boolean enabled = true;

    /** 轮询间隔（秒）：每轮跑完再计下一轮 */
    private long intervalSeconds = 30;

    /**
     * 多条告警规则：每条形如 <code>{intervals: 3, percent: 0.5}</code>，含义是
     * "在过去 N 个轮询周期内的最大涨幅达 M% 即触发"。多条规则之间是"任一触发即推"，
     * 同一轮里多条规则同时命中会合并成一条推送。
     */
    private List<Rule> rules = new ArrayList<>();

    /** 钉钉机器人配置（落盘字段） */
    private DingTalkCfg dingtalk = new DingTalkCfg();

    /** 涨停 / 跌停股票的"放量"判断阈值（页面可改） */
    private SurgeCfg surge = new SurgeCfg();

    /** 盯盘股票集合 */
    private List<StockRef> stocks = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules == null ? new ArrayList<>() : rules;
    }

    public DingTalkCfg getDingtalk() {
        return dingtalk;
    }

    public void setDingtalk(DingTalkCfg dingtalk) {
        this.dingtalk = dingtalk == null ? new DingTalkCfg() : dingtalk;
    }

    public SurgeCfg getSurge() {
        return surge;
    }

    public void setSurge(SurgeCfg surge) {
        this.surge = surge == null ? new SurgeCfg() : surge;
    }

    public List<StockRef> getStocks() {
        return stocks;
    }

    public void setStocks(List<StockRef> stocks) {
        this.stocks = stocks == null ? new ArrayList<>() : stocks;
    }

    /** 单条告警规则 */
    public static class Rule {
        /**
         * 过去时长（按上方轮询间隔的倍数）：与 N×intervalSeconds 秒前最近一次采样价比较。
         * 例：轮询间隔 60s、intervals=3 → 比较"180s 前的采样"。
         */
        private int intervals;
        /** 偏离百分比（绝对值）：涨跌幅达到此值就触发 */
        private double percent;

        public Rule() {
        }

        public Rule(int intervals, double percent) {
            this.intervals = intervals;
            this.percent = percent;
        }

        public int getIntervals() {
            return intervals;
        }

        public void setIntervals(int intervals) {
            this.intervals = intervals;
        }

        public double getPercent() {
            return percent;
        }

        public void setPercent(double percent) {
            this.percent = percent;
        }

        /** 兼容老落盘文件：老字段叫 seconds（绝对秒数），读不到 intervals 时用它回填 */
        public long getSeconds() {
            return intervals;
        }

        public void setSeconds(long seconds) {
            // 老数据：把"过去多少秒"换算成"多少个轮询周期"——只取整数倍，余数丢弃。
            // 这里没法拿到 intervalSeconds，所以只在反序列化时尽量保真。
            this.intervals = (int) Math.max(1, seconds);
        }
    }

    /**
     * <b>历史遗留字段，v1.5.9 起不再读写</b> —— 仅为兼容老落盘文件而保留。
     *
     * <p>保留的原因很实际：{@code WatchConfigStore} 用默认的 Jackson 解析落盘文件，
     * 类里少一个字段就会在读到老文件时抛「未知属性」，整份名单跟着丢失。
     * 现在的机器人统一从系统配置页取（{@link WatchConfigStore#webhookOf(long)}），
     * 保存配置时这个字段会被置空，落盘文件里不再残留凭据。
     *
     * <p>（v1.4 起钉钉只用「自定义关键词」模式，不再支持加签，所以连 secret 都没有了。）
     */
    public static class DingTalkCfg {
        /** Webhook 地址（含 access_token）；为空则用 yml 默认值 */
        private String webhook = "";

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }
    }

    /**
     * 涨停 / 跌停股票的"放量"判断阈值（落盘字段，页面可改）。
     *
     * <p>两个条件满足任一即算放量：单个轮询间隔的成交额超过 {@code amountWan} 万元，
     * 或者大于上一个间隔成交额的 {@code times} 倍。只对当前封在涨停 / 跌停板上的标的生效。
     */
    public static class SurgeCfg {
        /** 单个轮询间隔的成交额阈值（万元）：超过它就算放量 */
        private double amountWan = 2000;
        /** 放量倍数：单个轮询间隔成交额大于上一间隔的多少倍算放量 */
        private double times = 10;

        public SurgeCfg() {
        }

        public SurgeCfg(double amountWan, double times) {
            this.amountWan = amountWan;
            this.times = times;
        }

        public double getAmountWan() {
            return amountWan;
        }

        public void setAmountWan(double amountWan) {
            this.amountWan = amountWan;
        }

        public double getTimes() {
            return times;
        }

        public void setTimes(double times) {
            this.times = times;
        }
    }

    /** 盯盘名单里的一只股票：代码必填，名称只是展示用（缺了按名录补） */
    public static class StockRef {

        private String code;
        private String name;

        public StockRef() {
        }

        public StockRef(String code, String name) {
            this.code = code;
            this.name = name;
        }

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }
    }
}
