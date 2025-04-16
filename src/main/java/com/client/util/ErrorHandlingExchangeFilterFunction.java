package com.client.network;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

public class ErrorHandlingExchangeFilterFunction implements ExchangeFilterFunction {

    private static final Logger logger = LoggerFactory.getLogger(ErrorHandlingExchangeFilterFunction.class);

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        long startTime = System.currentTimeMillis();

        logger.debug("发送请求: {} {}", request.method(), request.url());

        return next.exchange(request)
                .doOnSuccess(response -> {
                    long duration = System.currentTimeMillis() - startTime;
                    logger.debug("收到响应: {} {} - 状态码: {}, 耗时: {}ms",
                            request.method(), request.url(), response.statusCode(), duration);
                })
                .doOnError(error -> {
                    long duration = System.currentTimeMillis() - startTime;
                    logger.error("请求失败: {} {} - 耗时: {}ms, 错误: {}",
                            request.method(), request.url(), duration, error.getMessage());
                });
    }
}