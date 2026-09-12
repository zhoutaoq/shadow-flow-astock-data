package com.stock.kline.support;

import com.stock.kline.repo.SysConfigRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** 数据库模式的令牌来源：通用配置表（场景 waizao / 键 token） */
@Component
@ConditionalOnProperty(name = "waizao.db.enabled", havingValue = "true")
public class DbTokenProvider implements TokenProvider {

    private final SysConfigRepository sysConfig;

    public DbTokenProvider(SysConfigRepository sysConfig) {
        this.sysConfig = sysConfig;
    }

    @Override
    public String token() {
        return sysConfig.waizaoToken();
    }
}
