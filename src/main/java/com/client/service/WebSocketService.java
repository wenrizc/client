package com.client.service;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandler;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.springframework.web.socket.sockjs.client.SockJsClient;
import org.springframework.web.socket.sockjs.client.Transport;
import org.springframework.web.socket.sockjs.client.WebSocketTransport;

import com.client.config.AppConfig;
import com.client.model.Message;
import com.client.model.Room;
import com.client.model.User;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;

public class WebSocketService {
    private final String wsUrl;
    private final ObjectMapper objectMapper;
    private WebSocketStompClient stompClient;
    private StompSession stompSession;
    private final ScheduledExecutorService heartbeatScheduler;
    private final String username;
    private final ApiService apiService;
    private Consumer<Message> lobbyMessageHandler;
    private Consumer<Room> roomUpdateHandler;
    private Consumer<Map<String, Object>> roomDetailHandler;
    private Consumer<Message> roomMessageHandler;
    private Consumer<User> userStatusUpdateHandler;

    public WebSocketService(AppConfig config, String username, ApiService apiService) {
        this.wsUrl = config.getWebSocketUrl();
        this.objectMapper = new ObjectMapper();
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        this.username = username;
        this.apiService = apiService;
    }

    public void setLobbyMessageHandler(Consumer<Message> handler) {
        this.lobbyMessageHandler = handler;
    }

    public void setRoomUpdateHandler(Consumer<Room> handler) {
        this.roomUpdateHandler = handler;
    }

    public void setRoomDetailHandler(Consumer<Map<String, Object>> handler) {
        this.roomDetailHandler = handler;
    }

    public void setRoomMessageHandler(Consumer<Message> handler) {
        this.roomMessageHandler = handler;
    }

    public void connect() {
        WebSocketClient client = new StandardWebSocketClient();

        // 使用SockJS客户端
        WebSocketTransport webSocketTransport = new WebSocketTransport(client);
        List<Transport> transports = Collections.singletonList(webSocketTransport);
        SockJsClient sockJsClient = new SockJsClient(transports);

        // 从ApiService获取sessionId
        String sessionId = apiService.getSessionId();

        // 确保在URL中传递sessionId作为参数
        String connectionUrl = wsUrl + "?sessionId=" + sessionId;

        stompClient = new WebSocketStompClient(sockJsClient);
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());

        StompSessionHandler sessionHandler = new CustomStompSessionHandler();
        try {
            // 连接到SockJS端点，并传递会话ID
            stompClient.connect(connectionUrl, sessionHandler);
        } catch (Exception e) {
            System.err.println("无法连接到WebSocket服务器: " + e.getMessage());
        }
    }

    public void disconnect() {
        if (stompSession != null && stompSession.isConnected()) {
            stompSession.disconnect();
            stompSession = null;
        }

        heartbeatScheduler.shutdownNow();
    }

    public void logout() {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法发送退出登录请求");
            return;
        }

        stompSession.send("/app/user.logout", null);
        System.out.println("已发送退出登录请求");
    }

    private void startHeartbeatTask() {
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (stompSession != null && stompSession.isConnected()) {
                try {
                    Map<String, Object> heartbeat = new HashMap<>();
                    heartbeat.put("type", "heartbeat");
                    heartbeat.put("timestamp", System.currentTimeMillis());

                    stompSession.send("/app/user.heartbeat", heartbeat);
                } catch (Exception e) {
                    System.err.println("发送心跳失败: " + e.getMessage());
                }
            }
        }, 10, 10, TimeUnit.SECONDS);
    }

    public void setUserStatusUpdateHandler(Consumer<User> handler) {
        this.userStatusUpdateHandler = handler;
    }

    private class CustomStompSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            stompSession = session;

            // 发送连接消息
            Map<String, Object> connectMessage = new HashMap<>();
            connectMessage.put("username", username);
            session.send("/app/user.connect", connectMessage);

            // 添加订阅错误消息通道
            session.subscribe("/user/queue/errors", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        JsonNode node = (JsonNode) payload;
                        String type = node.path("type").asText();
                        String message = node.path("message").asText();

                        if ("CONNECTION_REJECTED".equals(type)) {
                            Platform.runLater(() -> {
                                // 创建一个错误事件或回调
                                if (connectionErrorHandler != null) {
                                    connectionErrorHandler.accept(message);
                                }
                            });
                        }
                    } catch (Exception e) {
                        System.err.println("处理错误消息失败: " + e.getMessage());
                    }
                }
            });

            // 订阅用户状态更新
            session.subscribe("/topic/users.status", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    JsonNode statusUpdate = (JsonNode) payload;
                    String username = statusUpdate.path("username").asText();
                    boolean online = statusUpdate.path("online").asBoolean();

                    User user = new User();
                    user.setUsername(username);
                    user.setActive(online);

                    if (userStatusUpdateHandler != null) {
                        Platform.runLater(() -> userStatusUpdateHandler.accept(user));
                    }
                }
            });

            // 订阅个人消息
            session.subscribe("/user/queue/heartbeat", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    // 处理心跳响应
                    System.out.println("收到心跳响应");
                }
            });

            // 订阅大厅消息
            session.subscribe("/topic/lobby.messages", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        JsonNode node = (JsonNode) payload;
                        Message message = new Message(
                                node.path("sender").asText(),
                                node.path("message").asText(),
                                node.path("timestamp").asLong(),
                                node.path("type").asText("LOBBY_MESSAGE"));

                        if (lobbyMessageHandler != null) {
                            Platform.runLater(() -> lobbyMessageHandler.accept(message));
                        }
                    } catch (Exception e) {
                        System.err.println("处理大厅消息出错: " + e.getMessage());
                    }
                }
            });

            // 订阅房间更新
            session.subscribe("/topic/rooms.updates", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        JsonNode node = (JsonNode) payload;
                        if (roomUpdateHandler != null) {
                            Platform.runLater(() -> {
                                try {
                                    Room room = objectMapper.treeToValue(node, Room.class);
                                    roomUpdateHandler.accept(room);
                                } catch (Exception e) {
                                    System.err.println("解析房间数据出错: " + e.getMessage());
                                }
                            });
                        }
                    } catch (Exception e) {
                        System.err.println("处理房间更新出错: " + e.getMessage());
                    }
                }
            });

            // 订阅个人房间详情
            session.subscribe("/user/queue/room.detail", new StompFrameHandler() {
                @Override
                public Type getPayloadType(StompHeaders headers) {
                    return JsonNode.class;
                }

                @Override
                public void handleFrame(StompHeaders headers, Object payload) {
                    try {
                        JsonNode node = (JsonNode) payload;
                        if (roomDetailHandler != null) {
                            Platform.runLater(() -> {
                                try {
                                    Map<String, Object> roomDetail = objectMapper.convertValue(node, Map.class);
                                    roomDetailHandler.accept(roomDetail);
                                } catch (Exception e) {
                                    System.err.println("解析房间详情出错: " + e.getMessage());
                                }
                            });
                        }
                    } catch (Exception e) {
                        System.err.println("处理房间详情出错: " + e.getMessage());
                    }
                }
            });

            // 启动心跳
            startHeartbeatTask();
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                StompHeaders headers, byte[] payload, Throwable exception) {
            System.err.println("WebSocket连接错误: " + exception.getMessage());
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            System.err.println("WebSocket传输错误: " + exception.getMessage());
            // 尝试重连
            try {
                Thread.sleep(3000);
                connect();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // 添加连接错误处理器
    private Consumer<String> connectionErrorHandler;

    public void setConnectionErrorHandler(Consumer<String> handler) {
        this.connectionErrorHandler = handler;
    }

    // 添加方法发送消息到大厅
    public void sendLobbyMessage(String message) {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法发送消息");
            return;
        }

        Map<String, Object> chatMessage = new HashMap<>();
        chatMessage.put("message", message);
        stompSession.send("/app/lobby.message", chatMessage);
    }

    // 添加方法创建房间
    public void createRoom(String roomName, String gameType, int maxPlayers) {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法创建房间");
            return;
        }

        Map<String, Object> roomInfo = new HashMap<>();
        roomInfo.put("roomName", roomName);
        roomInfo.put("gameType", gameType);
        roomInfo.put("maxPlayers", maxPlayers);
        stompSession.send("/app/room.create", roomInfo);
    }

    // 添加方法加入房间
    public void joinRoom(Long roomId) {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法加入房间");
            return;
        }

        Map<String, Object> request = new HashMap<>();
        request.put("roomId", roomId);
        stompSession.send("/app/room.join", request);
    }

    // 添加方法离开房间
    public void leaveRoom() {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法离开房间");
            return;
        }

        stompSession.send("/app/room.leave", null);
    }

    // 添加方法发送消息到房间
    public void sendRoomMessage(Long roomId, String message) {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法发送房间消息");
            return;
        }

        Map<String, Object> chatMessage = new HashMap<>();
        chatMessage.put("roomId", roomId);
        chatMessage.put("message", message);
        stompSession.send("/app/room.message", chatMessage);
    }

    // 添加方法订阅房间消息
    public void subscribeToRoomMessages(Long roomId) {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法订阅房间消息");
            return;
        }

        String destination = "/topic/room." + roomId + ".messages";
        stompSession.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    JsonNode node = (JsonNode) payload;
                    Message message = new Message(
                            node.path("sender").asText(),
                            node.path("message").asText(),
                            node.path("timestamp").asLong(),
                            "ROOM_MESSAGE");

                    if (roomMessageHandler != null) {
                        Platform.runLater(() -> roomMessageHandler.accept(message));
                    }
                } catch (Exception e) {
                    System.err.println("处理房间消息出错: " + e.getMessage());
                }
            }
        });
    }

    // 添加方法发送开始游戏请求
    public void startGame(Long roomId) {
        if (stompSession == null || !stompSession.isConnected()) {
            System.err.println("WebSocket未连接，无法发送开始游戏请求");
            return;
        }

        Map<String, Object> request = new HashMap<>();
        request.put("roomId", roomId);
        stompSession.send("/app/room.start", request);
    }

}
