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

    /** 盯盘名单文件（每行一个股票名称） */
    private String watchListFile = "resources/watch-list.txt";

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
        /** Webhook 地址（含 access_token）；为空则不推送 */
        private String webhook = "";
        /** 加签密钥；为空表示不加签 */
        private String secret = "";

        public String getWebhook() {
            return webhook;
        }

        public void setWebhook(String webhook) {
            this.webhook = webhook;
        }

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public boolean configured() {
            return webhook != null && !webhook.trim().isEmpty();
        }
    }
}
