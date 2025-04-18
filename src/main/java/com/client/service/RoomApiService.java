package com.client.service;

import com.client.exception.ApiException;
import com.client.model.Room;
import com.client.util.SessionManager;
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

/**
 * 房间API服务
 * <p>
 * 处理与房间相关的API调用，包括创建、加入、离开房间
 * 以及房间信息查询和游戏状态管理
 * </p>
 */
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
     * 获取可加入的房间列表
     *
     * @return 可加入的房间列表
     * @throws ApiException 如果用户未登录或获取失败
     */
    public List<Room> getRoomList() {
        validateSession();

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
     *
     * @return 用户当前所在房间，如果用户不在任何房间则返回null
     * @throws ApiException 如果用户未登录或获取失败
     */
    public Room getCurrentUserRoom() {
        validateSession();

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
            return null;
        }
    }

    /**
     * 创建新房间
     *
     * @param roomName 房间名称
     * @param gameName 游戏名称
     * @param maxPlayers 最大玩家数
     * @return 创建的房间信息
     * @throws ApiException 如果用户未登录或创建失败
     */
    public Room createRoom(String roomName, String gameName, int maxPlayers) {
        validateSession();

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
     * 加入指定房间
     *
     * @param roomId 房间ID
     * @return 加入的房间信息
     * @throws ApiException 如果用户未登录或加入失败
     */
    public Room joinRoom(Long roomId) {
        validateSession();

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
     * 离开当前房间
     *
     * @return 是否成功离开
     * @throws ApiException 如果用户未登录
     */
    public boolean leaveRoom() {
        validateSession();

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
     * 开始游戏
     *
     * @param roomId 房间ID
     * @return 更新后的房间信息
     * @throws ApiException 如果用户未登录或开始失败
     */
    public Room startGame(Long roomId) {
        validateSession();

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
     *
     * @param roomId 房间ID
     * @return 更新后的房间信息
     * @throws ApiException 如果用户未登录或结束失败
     */
    public Room endGame(Long roomId) {
        validateSession();

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
}