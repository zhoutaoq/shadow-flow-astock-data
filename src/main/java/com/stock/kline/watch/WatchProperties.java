package com.stock.kline.watch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实时盯盘提醒的配置，绑定前缀 <code>watch</code>。
 *
 * <p>本模块与系统其它功能没有代码耦合：行情取自新浪财经、不连数据库、不走 waizao 供应商、
 * 也不落任何表，只依赖下面这一段配置。
 */
@Component
@ConfigurationProperties(prefix = "watch")
public class WatchProperties {

    /** 总开关 */
    private boolean enabled = true;

    /** 盯盘名单文件（每行一个股票名称）；只在首次启动、还没有落盘配置时用来迁移名单 */
    private String watchListFile = "resources/watch-list.txt";

    /**
     * 落盘配置文件：页面保存的开关 / 间隔 / 阈值 / 股票集合都写在这里。
     *
     * <p>解析顺序是「工作目录下的 watch-config.json → 这里配置的绝对路径」，
     * 所以服务器部署时两个通常指向同一个文件。
     */
    private String configFile = "/home/stock-backtesting/watch-config.json";

    /** 名称 → 代码 索引的数据文件 */
    private String stockListFile = "resources/stock-list.json";

    /** 轮询间隔（秒） */
    private long intervalSeconds = 30;

    /** 触发阈值（百分比，与上次查询价格的偏离绝对值） */
    private double thresholdPercent = 1.0d;

    /** 并发查询线程池大小 */
    private int threadPoolSize = 8;

    private final Sina sina = new Sina();

    private final DingTalk dingtalk = new DingTalk();

    private final Surge surge = new Surge();

    private final StartupNotify startupNotify = new StartupNotify();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getWatchListFile() {
        return watchListFile;
    }

    public void setWatchListFile(String watchListFile) {
        this.watchListFile = watchListFile;
    }

    public String getConfigFile() {
        return configFile;
    }

    public void setConfigFile(String configFile) {
        this.configFile = configFile;
    }

    public String getStockListFile() {
        return stockListFile;
    }

    public void setStockListFile(String stockListFile) {
        this.stockListFile = stockListFile;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public void setIntervalSeconds(long intervalSeconds) {
        this.intervalSeconds = intervalSeconds;
    }

    public double getThresholdPercent() {
        return thresholdPercent;
    }

    public void setThresholdPercent(double thresholdPercent) {
        this.thresholdPercent = thresholdPercent;
    }

    public int getThreadPoolSize() {
        return threadPoolSize;
    }

    public void setThreadPoolSize(int threadPoolSize) {
        this.threadPoolSize = threadPoolSize;
    }

    public Sina getSina() {
        return sina;
    }

    public DingTalk getDingtalk() {
        return dingtalk;
    }

    public Surge getSurge() {
        return surge;
    }

    public StartupNotify getStartupNotify() {
        return startupNotify;
    }

    /**
     * 启动通知：系统起来后推一条钉钉启动消息（含生产发布标识与版本号）。
     *
     * <p><b>默认关</b>——本地开发反复重启会一直打扰群里，所以本地不发；
     * 生产发布时在启动命令上显式打开：<code>--watch.startup-notify.enabled=true</code>。
     */
    public static class StartupNotify {
        /** 是否推送启动通知；默认 false = 本地不发消息 */
        private boolean enabled = false;
        /** 版本号：留空表示自动取 jar 清单里的 Implementation-Version（就是 pom 的 version） */
        private String version = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version == null ? "" : version;
        }
    }

    /**
     * 涨停 / 跌停股票的"放量"判断默认阈值。
     *
     * <p>只在「首次启动、还没有落盘配置文件」时作为初始值，之后一律以落盘配置（页面可改）为准。
     */
    public static class Surge {
        /** 单个轮询间隔的成交额超过多少万元算放量 */
        private double amountWan = 2000;
        /** 单个轮询间隔的成交额大于上一个间隔的多少倍算放量 */
        private double times = 10;

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

    /** 新浪行情接口 */
    public static class Sina {
        private String baseUrl = "http://hq.sinajs.cn";
        private int connectTimeoutMs = 5000;
        private int readTimeoutMs = 5000;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getReadTimeoutMs() {
            return readTimeoutMs;
        }

        public void setReadTimeoutMs(int readTimeoutMs) {
            this.readTimeoutMs = readTimeoutMs;
        }
    }

    /** 钉钉机器人 */
    public static class DingTalk {
        /**
         * <b>钉钉推送总开关</b>：false 时一条消息都发不出去（盯盘告警、启动通知、用户反馈全部拦下），
         * 只在应用日志里留痕。
         *
         * <p><b>默认 false</b> —— 本地开发反复启停，不该往群里刷消息。
         * 需要真推送时用 yml 或启动参数显式打开：<code>--watch.dingtalk.enabled=true</code>。
         *
         * <p>它和 {@code watch.startup-notify.enabled} 是两道独立的闸：
         * 启动通知还要再过一道「只在生产发布时发」的判断。
         */
        private boolean enabled = false;

        /**
         * Webhook 地址（含 access_token）；为空则不推送。
         *
         * <p>v1.4 起钉钉机器人只用"自定义关键词"模式（消息必须含特定关键词才会被服务端放行），
         * 不再支持「加签」，所以本类不再暴露 secret 字段。
         */
        private String webhook = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }

        public boolean configured() {
            return webhook != null && !webhook.trim().isEmpty();
        }

        /** 是否真的能发：总开关打开 + webhook 已配置 */
        public boolean pushEnabled() {
            return enabled && configured();
        }
    }
}
