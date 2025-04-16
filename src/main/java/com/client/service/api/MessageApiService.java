package com.client.service.api;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.client.model.Message;
import com.client.network.ApiException;
import com.client.session.SessionManager;

import reactor.core.publisher.Mono;

@Service
public class MessageApiService {
    private static final Logger logger = LoggerFactory.getLogger(MessageApiService.class);

    private final WebClient webClient;
    private final SessionManager sessionManager;

    @Autowired
    public MessageApiService(WebClient webClient, SessionManager sessionManager) {
        this.webClient = webClient;
        this.sessionManager = sessionManager;
    }

    /**
     * 发送大厅消息
     */
    public boolean sendLobbyMessage(String message) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        Map<String, String> request = new HashMap<>();
        request.put("message", message);

        try {
            webClient.post()
                    .uri("/api/messages/lobby")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            response -> response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                            })
                                    .flatMap(errorBody -> {
                                        String errorMsg = String.valueOf(errorBody.getOrDefault("error", "发送消息失败"));
                                        return Mono.<Throwable>just(
                                                new ApiException(errorMsg, response.statusCode().value()));
                                    }))
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {
                    })
                    .block();
            return true;
        } catch (Exception e) {
            logger.error("发送大厅消息失败", e);
            return false;
        }
    }

    /**
     * 获取大厅消息历史
     */
    public List<Message> getLobbyMessageHistory() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.get()
                .uri("/api/messages/lobby/history")
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(),
                        response -> Mono.<Throwable>just(new ApiException("获取大厅消息历史失败", response.statusCode().value())))
                .bodyToMono(new ParameterizedTypeReference<List<Message>>() {
                })
                .block();
    }

    /**
     * 获取房间消息历史
     */
    public List<Message> getRoomMessageHistory(Long roomId) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.get()
                .uri("/api/messages/room/{roomId}/history", roomId)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(),
                        response -> Mono.<Throwable>just(new ApiException("获取房间消息历史失败", response.statusCode().value())))
                .bodyToMono(new ParameterizedTypeReference<List<Message>>() {
                })
                .block();
    }

    /**
     * 获取房间消息
     */
    public List<Message> getRoomMessages(Long roomId) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        try {
            return webClient.get()
                    .uri("/api/messages/room/{roomId}/history", roomId)
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            response -> Mono.error(new ApiException("获取房间消息失败", response.statusCode().value())))
                    .bodyToFlux(Message.class)
                    .collectList()
                    .block();
        } catch (Exception e) {
            if (e instanceof ApiException) {
                throw e;
            }
            throw new ApiException("获取房间消息失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 发送房间消息
     */
    public void sendRoomMessage(Long roomId, String message) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        Map<String, Object> request = new HashMap<>();
        request.put("message", message);

        webClient.post()
                .uri("/api/messages/room/{roomId}", roomId)
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.bodyToMono(Map.class)
                        .flatMap(errorBody -> {
                            String err = String.valueOf(errorBody.getOrDefault("error", "发送消息失败"));
                            return Mono.error(new ApiException(err, response.statusCode().value()));
                        }))
                .bodyToMono(Void.class)
                .block();
    }
}