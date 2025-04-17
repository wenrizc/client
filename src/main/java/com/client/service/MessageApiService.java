package com.client.service;

import com.client.model.Message;
import com.client.exception.ApiException;
import com.client.util.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消息API服务
 * <p>
 * 处理与聊天消息相关的API调用，包括发送和获取大厅/房间消息
 * </p>
 */
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
     *
     * @param message 消息内容
     * @return 发送是否成功
     * @throws ApiException 如果用户未登录或发送失败
     */
    public boolean sendLobbyMessage(String message) {
        validateSession();

        if (message == null || message.trim().isEmpty()) {
            throw new ApiException("消息不能为空", 400);
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
                            response -> response.bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                                    .flatMap(errorBody -> {
                                        String errorMsg = String.valueOf(errorBody.getOrDefault("error", "发送消息失败"));
                                        return Mono.error(new ApiException(errorMsg, response.statusCode().value()));
                                    }))
                    .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                    .block();
            return true;
        } catch (Exception e) {
            logger.error("发送大厅消息失败", e);
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("发送大厅消息失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 获取大厅消息历史
     *
     * @return 大厅消息列表
     * @throws ApiException 如果用户未登录或获取失败
     */
    public List<Message> getLobbyMessageHistory() {
        validateSession();

        try {
            return webClient.get()
                    .uri("/api/messages/lobby/history")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(),
                            response -> Mono.error(new ApiException("获取大厅消息历史失败", response.statusCode().value())))
                    .bodyToMono(new ParameterizedTypeReference<List<Message>>() {})
                    .block();
        } catch (Exception e) {
            logger.error("获取大厅消息历史失败", e);
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("获取大厅消息历史失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 获取房间消息历史
     *
     * @param roomId 房间ID
     * @return 房间消息列表
     * @throws ApiException 如果用户未登录、房间ID为空或获取失败
     */
    public List<Message> getRoomMessages(Long roomId) {
        validateSession();

        if (roomId == null) {
            throw new ApiException("房间ID不能为空", 400);
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
            logger.error("获取房间消息失败: {}", e.getMessage());
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("获取房间消息失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 发送房间消息
     *
     * @param roomId 房间ID
     * @param message 消息内容
     * @throws ApiException 如果用户未登录、房间ID为空、消息为空或发送失败
     */
    public void sendRoomMessage(Long roomId, String message) {
        validateSession();

        if (roomId == null) {
            throw new ApiException("房间ID不能为空", 400);
        }

        if (message == null || message.trim().isEmpty()) {
            throw new ApiException("消息不能为空", 400);
        }

        try {
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
        } catch (Exception e) {
            logger.error("发送房间消息失败", e);
            if (e instanceof ApiException) {
                throw (ApiException) e;
            }
            throw new ApiException("发送房间消息失败: " + e.getMessage(), 500);
        }
    }

    /**
     * 验证用户会话是否有效
     *
     * @throws ApiException 如果用户未登录
     */
    private void validateSession() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }
    }

    /**
     * @deprecated 使用 {@link #getRoomMessages(Long)} 代替
     */
    @Deprecated
    public List<Message> getRoomMessageHistory(Long roomId) {
        return getRoomMessages(roomId);
    }
}