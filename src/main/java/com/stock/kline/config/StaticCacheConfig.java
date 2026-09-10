package com.stock.kline.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * 静态资源缓存分级。
 *
 * <p>背景：application.yml 里全局 no-store 是开发期防"改了没生效"的兜底，
 * 但生产上服务器带宽只有 ~1Mbps，echarts.min.js 有 1MB，no-store 会让
 * 每次打开页面都重新拉这 1MB（实测 8 秒），是"页面加载很慢"的主因。
 *
 * <p>策略：
 * <ul>
 *   <li>css / js / vendor（echarts）→ 缓存 7 天。这些 URL 全部带
 *       ?v=20260910x 版本戳，发版即换 URL，不存在吃旧资源的问题；</li>
 *   <li>HTML 页面 → 维持全局 no-store，保证页面永远最新。</li>
 * </ul>
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/css/**", "/js/**", "/vendor/**")
                .addResourceLocations("classpath:/static/css/", "classpath:/static/js/", "classpath:/static/vendor/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic());
    }
}
