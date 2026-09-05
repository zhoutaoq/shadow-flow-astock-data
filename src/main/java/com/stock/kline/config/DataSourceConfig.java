package com.stock.kline.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import javax.sql.DataSource;

/**
 * 数据源按 waizao.db.enabled 条件装配。
 *
 * <p>无数据库模式（默认）下这里不创建任何数据源，应用就是一个不依赖外部存储的单机服务；
 * 只有显式开启数据库时，才按 spring.datasource.* 建 HikariDataSource。
 *
 * <p>注意：application.yml 里已排除 DataSourceAutoConfiguration，
 * 否则无论开关怎么设，启动阶段都会去连库。
 */
@Configuration
public class DataSourceConfig {

    /** spring.datasource 下的 url / username / password / driver-class-name */
    @Bean
    @ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties dataSourceProperties() {
        return new DataSourceProperties();
    }

    /**
     * 连接池。
     *
     * <p>分两步绑：先用 DataSourceProperties 填连接信息，再把 spring.datasource.hikari.*
     * 绑到 HikariDataSource 上 —— 直接在 DataSource 上绑 "spring.datasource" 只能拿到连接信息，
     * 池参数（maximum-pool-size 等）会静默丢失。
     */
    @Bean
    @ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        HikariDataSource ds = properties.initializeDataSourceBuilder()
                .type(HikariDataSource.class).build();
        ds.setPoolName("kline-hikari");
        return ds;
    }

    /** JdbcTemplateAutoConfiguration 也在排除列表里，JdbcTemplate 得自己建 */
    @Bean
    @ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean
    @ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
    public NamedParameterJdbcTemplate namedParameterJdbcTemplate(DataSource dataSource) {
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
