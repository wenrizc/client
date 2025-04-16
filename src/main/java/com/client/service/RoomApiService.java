package com.client.service.api;

import com.client.model.Room;
import com.client.exception.ApiException;
import com.client.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RoomApiService {
    private static final Logger logger = LoggerFactory.getLogger(RoomApiService.class);

    private final WebClient webClient;
    private final SessionManager sessionManager;

    @Autowired
    public RoomApiService(WebClient webClient, SessionManager sessionManager) {
        this.webClient = webClient;
        this.sessionManager = sessionManager;
    }

    /**
     * 创建房间
     */
    public Room createRoom(String roomName, String gameName, int maxPlayers) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        Map<String, Object> request = new HashMap<>();
        request.put("roomName", roomName);
        request.put("gameName", gameName);
        request.put("maxPlayers", maxPlayers);

        return webClient.post()
                .uri("/api/rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .bodyValue(request)
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(Map.class)
                                .flatMap(errorBody -> {
                                    String message = String.valueOf(errorBody.getOrDefault("error", "创建房间失败"));
                                    return Mono.error(new ApiException(message, response.statusCode().value()));
                                })
                )
                .bodyToMono(Room.class)
                .block();
    }

    /**
     * 加入房间
     */
    public Room joinRoom(Long roomId) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.post()
                .uri("/api/rooms/{roomId}/join", roomId)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(Map.class)
                                .flatMap(errorBody -> {
                                    String message = String.valueOf(errorBody.getOrDefault("error", "加入房间失败"));
                                    return Mono.error(new ApiException(message, response.statusCode().value()));
                                })
                )
                .bodyToMono(Room.class)
                .block();
    }

    /**
     * 离开房间
     */
    public boolean leaveRoom() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        try {
            webClient.post()
                    .uri("/api/rooms/leave")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.isError(), response ->
                            response.bodyToMono(Map.class)
                                    .flatMap(errorBody -> {
                                        String message = String.valueOf(errorBody.getOrDefault("error", "离开房间失败"));
                                        return Mono.error(new ApiException(message, response.statusCode().value()));
                                    })
                    )
                    .bodyToMono(Map.class)
                    .block();
            return true;
        } catch (Exception e) {
            logger.error("离开房间时出错", e);
            return false;
        }
    }

    /**
     * 获取房间列表
     */
    public List<Room> getRoomList() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.get()
                .uri("/api/rooms")
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        Mono.error(new ApiException("获取房间列表失败", response.statusCode().value()))
                )
                .bodyToMono(new ParameterizedTypeReference<List<Room>>() {})
                .block();
    }

    /**
     * 获取当前用户所在房间
     */
    public Room getCurrentUserRoom() {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        try {
            return webClient.get()
                    .uri("/api/rooms/my-room")
                    .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                    .retrieve()
                    .onStatus(status -> status.equals(HttpStatus.NOT_FOUND), response -> Mono.empty())
                    .onStatus(status -> status.isError(), response ->
                            Mono.error(new ApiException("获取当前房间失败", response.statusCode().value()))
                    )
                    .bodyToMono(Room.class)
                    .block();
        } catch (Exception e) {
            if (e instanceof ApiException) {
                throw e;
            }
            return null; // 用户不在任何房间
        }
    }

    /**
     * 获取房间详情
     */
    public Room getRoomInfo(Long roomId) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.get()
                .uri("/api/rooms/{roomId}", roomId)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        Mono.error(new ApiException("获取房间详情失败", response.statusCode().value()))
                )
                .bodyToMono(Room.class)
                .block();
    }

    /**
     * 开始游戏
     */
    public Room startGame(Long roomId) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.post()
                .uri("/api/rooms/{roomId}/start", roomId)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(Map.class)
                                .flatMap(errorBody -> {
                                    String message = String.valueOf(errorBody.getOrDefault("error", "开始游戏失败"));
                                    return Mono.error(new ApiException(message, response.statusCode().value()));
                                })
                )
                .bodyToMono(Room.class)
                .block();
    }

    /**
     * 结束游戏
     */
    public Room endGame(Long roomId) {
        if (!sessionManager.hasValidSession()) {
            throw new ApiException("用户未登录", 401);
        }

        return webClient.post()
                .uri("/api/rooms/{roomId}/end", roomId)
                .header("Cookie", "JSESSIONID=" + sessionManager.getSessionId())
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.bodyToMono(Map.class)
                                .flatMap(errorBody -> {
                                    String message = String.valueOf(errorBody.getOrDefault("error", "结束游戏失败"));
                                    return Mono.error(new ApiException(message, response.statusCode().value()));
                                })
                )
                .bodyToMono(Room.class)
                .block();
    }
}