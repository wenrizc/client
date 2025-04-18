package com.client.config;

import com.client.util.ErrorHandlingExchangeFilterFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;


@Configuration
public class WebClientConfig {

    private final AppProperties appProperties;

    @Autowired
    public WebClientConfig(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    /**
     * 创建WebClient Bean
     *
     * @return 配置好的WebClient实例
     */
    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .baseUrl(appProperties.getServerUrl())
                .filter(new ErrorHandlingExchangeFilterFunction())
                .defaultHeader("User-Agent", "GameHallClient/" + appProperties.getVersion())
                .build();
    }

    /**
     * 提供WebClient.Builder Bean以支持自定义WebClient创建
     *
     * @return 预配置的WebClient.Builder实例
     */
    @Bean
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder()
                .baseUrl(appProperties.getServerUrl())
                .filter(new ErrorHandlingExchangeFilterFunction());
    }
}