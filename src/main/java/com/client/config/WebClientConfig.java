package com.client.config;

import com.client.network.ErrorHandlingExchangeFilterFunction;
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

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .baseUrl(appProperties.getServerUrl())
                .filter(new ErrorHandlingExchangeFilterFunction())
                .build();
    }
}