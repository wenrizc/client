package com.client.service;

import com.client.config.AppProperties;
import com.client.model.Message;
import com.client.model.Room;
import com.client.network.ConnectionState;
import com.client.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import jakarta.annotation.PreDestroy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class WebSocketService {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketService.class);
    private static final int RECONNECT_DELAY_MS = 3000;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final int CONNECT_TIMEOUT_SECONDS = 10;

    private final SessionManager sessionManager;
    private final WebSocketStompClient stompClient;
    private final AppProperties appProperties;
    private final TaskScheduler taskScheduler;

    private StompSession stompSession;
    private final ConcurrentHashMap<String, SubscriptionEntry<?>> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);

    @Autowired
    public WebSocketService(SessionManager sessionManager, WebSocketStompClient stompClient,
                            AppProperties appProperties, TaskScheduler taskScheduler) {
        this.sessionManager = sessionManager;
        this.stompClient = stompClient;
        this.appProperties = appProperties;
        this.taskScheduler = taskScheduler;

        // 使用JavaFX属性绑定监听会话状态变化
        this.sessionManager.sessionIdProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty()) {
                connect();
            } else {
                disconnect();
            }
        });

        // 初始检查连接状态
        if (sessionManager.getSessionId() != null && !sessionManager.getSessionId().isEmpty()) {
            connect();
        }
    }

    /**
     * 获取当前连接状态
     */
    public ConnectionState getConnectionState() {
        if (stompSession != null && stompSession.isConnected()) {
            return ConnectionState.CONNECTED;
        } else if (reconnecting.get()) {
            return ConnectionState.CONNECTING;
        } else {
            return ConnectionState.DISCONNECTED;
        }
    }

    /**
     * 连接到WebSocket服务器
     */
    public synchronized boolean connect() {
        if (isConnected()) {
            logger.debug("WebSocket已连接");
            return true;
        }

        if (!sessionManager.hasValidSession()) {
            logger.warn("未登录，无法连接WebSocket");
            return false;
        }

        String sessionId = sessionManager.getSessionId();
        if (sessionId == null || sessionId.trim().isEmpty()) {
            logger.error("无法连接WebSocket：无效的会话ID");
            return false;
        }

        try {
            // 标记为正在重连
            reconnecting.set(true);
            // 使用http/https协议而非ws/wss
            String wsUrl = appProperties.getWsServerUrl();
            // 确保URL使用http/https协议
            if (wsUrl.startsWith("ws://")) {
                wsUrl = "http://" + wsUrl.substring(5);
            } else if (wsUrl.startsWith("wss://")) {
                wsUrl = "https://" + wsUrl.substring(6);
            }

            // 构建URL并附加会话ID
            wsUrl = wsUrl + "?sessionId=" + sessionId;
            logger.info("连接WebSocket: {}", wsUrl);

            // 尝试连接
            stompSession = stompClient.connect(wsUrl, new ClientSessionHandler())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);


            // 重置重连计数
            reconnectAttempts.set(0);
            reconnecting.set(false);

            // 重新订阅
            resubscribeAll();

            // 发送连接消息
            sendConnectMessage();

            logger.info("WebSocket连接成功");
            return true;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("WebSocket连接失败: {}", e.getMessage());
            scheduleReconnect();
            return false;
        }
    }

    /**
     * 断开WebSocket连接
     */
    @PreDestroy
    public synchronized void disconnect() {
        if (isConnected()) {
            try {
                stompSession.disconnect();
                logger.info("WebSocket已断开连接");
            } catch (Exception e) {
                logger.error("断开WebSocket连接时出错: {}", e.getMessage());
            } finally {
                stompSession = null;
            }
        }

        // 取消重连
        reconnecting.set(false);
    }

    /**
     * 安排重连任务
     */
    private void scheduleReconnect() {
        if (reconnecting.get() || !sessionManager.hasValidSession()) {
            return;
        }

        int attempts = reconnectAttempts.incrementAndGet();
        if (attempts > MAX_RECONNECT_ATTEMPTS) {
            logger.error("已达到最大重连次数 ({})", MAX_RECONNECT_ATTEMPTS);
            reconnecting.set(false);
            return;
        }

        long delay = RECONNECT_DELAY_MS * attempts;
        logger.info("将在 {}ms 后重连 (尝试 {}/{})", delay, attempts, MAX_RECONNECT_ATTEMPTS);

        reconnecting.set(true);
        taskScheduler.schedule(() -> {
            reconnecting.set(false);
            connect();
        }, new java.util.Date(System.currentTimeMillis() + delay));
    }

    /**
     * 订阅指定目的地的消息
     *
     * @param <T> 消息载荷类型
     * @param destination 目的地
     * @param payloadType 载荷类型
     * @param messageHandler 消息处理器
     */
    public <T> void subscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        logger.debug("订阅: {}", destination);

        // 存储订阅信息
        subscriptions.put(destination, new SubscriptionEntry<>(payloadType, messageHandler));

        // 如果已连接，立即订阅
        if (isConnected()) {
            doSubscribe(destination, payloadType, messageHandler);
        } else {
            // 未连接时尝试连接
            connect();
        }
    }

    /**
     * 执行实际订阅操作
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void doSubscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        stompSession.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return payloadType;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                try {
                    messageHandler.accept((T) payload);
                } catch (Exception e) {
                    logger.error("处理消息时出错: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * 重新订阅所有主题
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void resubscribeAll() {
        if (!isConnected() || subscriptions.isEmpty()) {
            return;
        }

        logger.info("重新订阅 {} 个主题", subscriptions.size());

        for (Map.Entry<String, SubscriptionEntry<?>> entry : subscriptions.entrySet()) {
            String destination = entry.getKey();
            SubscriptionEntry<?> subscription = entry.getValue();

            try {
                stompSession.subscribe(destination, new StompFrameHandler() {
                    @Override
                    public Type getPayloadType(StompHeaders headers) {
                        return subscription.getType();
                    }

                    @Override
                    public void handleFrame(StompHeaders headers, Object payload) {
                        try {
                            ((Consumer) subscription.getHandler()).accept(payload);
                        } catch (Exception e) {
                            logger.error("处理消息时出错: {}", e.getMessage());
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("重新订阅 {} 失败: {}", destination, e.getMessage());
            }
        }
    }

    /**
     * 发送连接消息
     */
    private void sendConnectMessage() {
        if (!isConnected() || sessionManager.getCurrentUser() == null) {
            return;
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CONNECT");
        payload.put("username", sessionManager.getCurrentUser().getUsername());

        send("/app/user.connect", payload);
    }

    /**
     * 发送消息到指定目的地
     */
    public void send(String destination, Object payload) {
        if (!isConnected()) {
            logger.warn("WebSocket未连接，无法发送消息到: {}", destination);
            connect();
            return;
        }

        try {
            stompSession.send(destination, payload);
        } catch (Exception e) {
            logger.error("发送消息到 {} 失败: {}", destination, e.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return stompSession != null && stompSession.isConnected();
    }

    // 常用订阅方法

    public void subscribeLobbyMessages(Consumer<Message> messageHandler) {
        subscribe("/topic/lobby.messages", Message.class, messageHandler);
    }

    public void subscribeRoomMessages(Long roomId, Consumer<Message> messageHandler) {
        subscribe("/topic/room." + roomId + ".messages", Message.class, messageHandler);
    }

    public void subscribeUserStatus(Consumer<Map> statusHandler) {
        subscribe("/topic/users.status", Map.class, statusHandler);
    }

    public void subscribeRoomUpdates(Consumer<Map> updateHandler) {
        subscribe("/topic/rooms.updates", Map.class, updateHandler);
    }

    public void subscribePersonalMessages(Consumer<Map> messageHandler) {
        subscribe("/user/queue/messages", Map.class, messageHandler);
    }

    public void subscribeErrors(Consumer<Map> errorHandler) {
        subscribe("/user/queue/errors", Map.class, errorHandler);
    }

    public void subscribeRoomDetails(Consumer<Room> roomDetailHandler) {
        subscribe("/user/queue/room.detail", Room.class, roomDetailHandler);
    }

    public void subscribeSystemNotifications(Consumer<Map> notificationHandler) {
        subscribe("/topic/system.notifications", Map.class, notificationHandler);
    }

    // 常用发送方法

    public void sendCreateRoom(String roomName, String gameName, int maxPlayers) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomName", roomName);
        payload.put("gameName", gameName);
        payload.put("maxPlayers", maxPlayers);
        send("/app/room.create", payload);
    }

    public void sendJoinRoom(Long roomId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", roomId);
        send("/app/room.join", payload);
    }

    public void sendLeaveRoom() {
        send("/app/room.leave", null);
    }

    public void sendStartGame(Long roomId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", roomId);
        send("/app/room.start", payload);
    }

    public void sendEndGame(Long roomId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", roomId);
        send("/app/room.end", payload);
    }

    public void sendRoomMessage(Long roomId, String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("roomId", roomId);
        payload.put("message", message);
        send("/app/room.message", payload);
    }

    public void sendLobbyMessage(String message) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("message", message);
        send("/app/lobby.message", payload);
    }

    public void sendHeartbeat() {
        send("/app/user.heartbeat", new HashMap<>());
    }

    /**
     * STOMP会话处理器
     */
    private class ClientSessionHandler extends StompSessionHandlerAdapter {
        @Override
        public void afterConnected(StompSession session, StompHeaders connectedHeaders) {
            logger.info("已连接到STOMP服务器");
        }

        @Override
        public void handleException(StompSession session, StompCommand command,
                                    StompHeaders headers, byte[] payload, Throwable exception) {
            logger.error("WebSocket异常: {}", exception.getMessage());
            scheduleReconnect();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            logger.error("WebSocket传输错误: {}", exception.getMessage());
            scheduleReconnect();
        }
    }

    /**
     * 订阅条目，存储类型和处理器
     */
    private static class SubscriptionEntry<T> {
        private final Class<T> type;
        private final Consumer<T> handler;

        public SubscriptionEntry(Class<T> type, Consumer<T> handler) {
            this.type = type;
            this.handler = handler;
        }

        public Consumer<T> getHandler() {
            return handler;
        }

        public Class<T> getType() {
            return type;
        }
    }
}