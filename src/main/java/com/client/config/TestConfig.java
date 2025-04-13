package com.client.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/**
 * 测试配置类
 * 控制各种测试功能的启用与禁用
 */
@Configuration
@Profile("dev") // 仅在开发环境中启用
public class TestConfig {

    /**
     * 启用网络测试配置
     */
    @Bean
    @ConditionalOnProperty(name = "app.network.test.enabled", havingValue = "true", matchIfMissing = false)
    public boolean networkTestEnabled() {
        return true;
    }
}