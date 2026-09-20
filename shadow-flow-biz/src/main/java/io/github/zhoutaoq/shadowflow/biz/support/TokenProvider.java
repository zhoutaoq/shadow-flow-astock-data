package io.github.zhoutaoq.shadowflow.biz.support;

/**
 * 行情数据服务令牌来源。
 *
 * <p>客户端只依赖本接口，不直接依赖配置表或请求上下文 ——
 * 令牌存在哪、谁配的、怎么改，对调用方都不该有差别。
 *
 * <p><b>v1.5.9 的口径</b>：令牌只认系统级那一份（{@code shadowflow_sys_config} 里归属 0 的行），
 * 全局一份、全站共用，实现见
 * {@link io.github.zhoutaoq.shadowflow.biz.config.IntegrationConfigService}。
 * 改造前「每个用户一份、本人优先」的做法已取消：对接第三方是系统级的事，
 * 不该因为某个用户没配过令牌就取不到数，也不必让每个人各存一份同样的凭据。
 */
public interface TokenProvider {

    /** 当前生效的令牌；为空表示尚未配置 */
    String token();
}
