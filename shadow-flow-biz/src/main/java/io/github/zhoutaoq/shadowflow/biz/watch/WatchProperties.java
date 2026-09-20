package io.github.zhoutaoq.shadowflow.biz.watch;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实时盯盘提醒的配置，绑定前缀 <code>watch</code>。
 *
 * <p>本模块与系统其它功能没有代码耦合：行情取自新浪财经、不连数据库、不经行情数据服务、
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

    /**
     * 群机器人的出厂兜底配置。
     *
     * <p><b>v1.6.0 收窄了本类</b>：原先这里还放「推送总开关 / 是否只在交易时段推送 / 时段串」，
     * 现已全部搬到通知 SDK 的 <code>shadowflow.notify.*</code> 下统一执行 ——
     * 那三项是「任何通知都要过的闸门」，散在业务模块里就意味着每个通知方都得自己记得判一次，
     * 漏一处就是线上事故。这里只留<b>业务归属</b>的东西：系统通知机器人是谁。
     */
    public static class DingTalk {
        /**
         * 系统通知机器人的 Webhook 兜底值（含 access_token）；为空则不推送。
         *
         * <p>⚠️ 真正生效的值由超管在「系统配置」页维护（系统级配置行 dingtalk/webhook），
         * 这里只是配置行没填时的出厂值。平台消息走它：发布通知 / 注册通知 / 用户反馈兜底；
         * 它同时是盯盘的<b>默认群</b> —— 用户没在「盯盘 → 推送」页配自己的机器人时告警发到这里。
         *
         * <p>机器人只用"自定义关键词"模式（消息必须含后台配置的关键词才会被放行），
         * 不支持「加签」，所以没有 secret 字段。
         */
        private String webhook = "";

        /**
         * 盯盘消息的关键词（品牌词）。
         *
         * <p>进群消息会拼成 <code>[关键词-主机] 时间 正文</code>；留空则不加标签。
         * 换机器人时改成对方后台配的那个词。
         */
        private String keyword = "智能盯盘";

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword == null ? "" : keyword;
        }

        public boolean configured() {
            return webhook != null && !webhook.trim().isEmpty();
        }
    }
}
