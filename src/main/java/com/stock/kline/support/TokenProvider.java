package com.stock.kline.support;

/**
 * 供应商令牌来源。
 *
 * <p>两种运行模式下令牌的出处不同，客户端只依赖本接口，不直接依赖配置表或请求上下文：
 * <ul>
 *   <li>数据库模式：维护在通用配置表（场景 waizao / 键 token），页面改完立即生效</li>
 *   <li>无数据库模式：维护在浏览器本地，每次请求通过请求头 X-Waizao-Token 带上来</li>
 * </ul>
 */
public interface TokenProvider {

    /** 当前生效的令牌；为空表示尚未配置 */
    String token();
}
