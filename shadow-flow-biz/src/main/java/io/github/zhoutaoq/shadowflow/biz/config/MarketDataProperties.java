package io.github.zhoutaoq.shadowflow.biz.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 行情数据服务接口的配置，绑定前缀 shadowflow */
@Component
@ConfigurationProperties(prefix = "shadowflow")
public class MarketDataProperties {

    private Api api = new Api();
    /** 数据库开关：false = 无数据库单机模式（数据现取现用、不落盘） */
    private Db db = new Db();
    /** stock-list.json 相对工作目录的路径 */
    private String stockListFile = "resources/stock-list.json";
    /**
     * 单次查询允许的最大自然日跨度。
     *
     * <p>无数据库模式下每次查询都要真的向行情数据服务发一次请求，跨度越大响应越慢、
     * 一次性返回的数据越多，这里设一个硬上限避免误填一个二十年区间。
     * v1.1 起放宽到 4000 天（近十年）。
     */
    private int maxSpanDays = 4000;

    public Api getApi() {
        return api;
    }

    public void setApi(Api api) {
        this.api = api;
    }

    public Db getDb() {
        return db;
    }

    public void setDb(Db db) {
        this.db = db;
    }

    /** 数据库相关配置 */
    public static class Db {
        /** 是否启用数据库；false（默认）= 无数据库单机模式 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    public String getStockListFile() {
        return stockListFile;
    }

    public void setStockListFile(String stockListFile) {
        this.stockListFile = stockListFile;
    }

    public int getMaxSpanDays() {
        return maxSpanDays;
    }

    public void setMaxSpanDays(int maxSpanDays) {
        this.maxSpanDays = maxSpanDays;
    }

    public static class Api {
        private String baseUrl = "http://api.waizaowang.com";
        /** 仅作新环境首次启动的种子值；日常维护在配置表（场景 shadowflow / 键 token） */
        private String token = "";
        /** 总开关：false 时抓取请求在发出前就被拦住 */
        private boolean enabled = true;
        private int connectTimeoutMs = 10000;
        private int readTimeoutMs = 20000;
        /** 串行抓取时每只股票之间的间隔（毫秒） */
        private long batchIntervalMs = 200;
        /** 批处理并发度：同时向行情数据服务发起请求的线程数 */
        private int fetchConcurrency = 8;
        /** 并发抓取时，每个工作线程处理完一只股票后的间隔（毫秒），用于限制 QPS */
        private long fetchIntervalMs = 100;

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
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

        public long getBatchIntervalMs() {
            return batchIntervalMs;
        }

        public void setBatchIntervalMs(long batchIntervalMs) {
            this.batchIntervalMs = batchIntervalMs;
        }

        public int getFetchConcurrency() {
            return fetchConcurrency;
        }

        public void setFetchConcurrency(int fetchConcurrency) {
            this.fetchConcurrency = fetchConcurrency;
        }

        public long getFetchIntervalMs() {
            return fetchIntervalMs;
        }

        public void setFetchIntervalMs(long fetchIntervalMs) {
            this.fetchIntervalMs = fetchIntervalMs;
        }
    }
}
