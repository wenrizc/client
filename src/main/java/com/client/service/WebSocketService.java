package com.client.service;

import com.client.config.AppProperties;
import com.client.model.Message;
import com.client.model.Room;
import com.client.network.ConnectionState;
import com.client.session.ConnectionFailedEvent;
import com.client.session.ReconnectSuccessEvent;
import com.client.session.SessionManager;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.stomp.*;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    private volatile StompSession stompSession;
    private final ConcurrentHashMap<String, SubscriptionEntry<?>> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final Object reconnectLock = new Object(); // 新增重连锁

    // 重连相关配置
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000; // 首次重连延迟增加到1秒
    private static final long MAX_RECONNECT_DELAY_MS = 30000; // 最长等待30秒

    // 心跳相关配置
    private static final long HEARTBEAT_INTERVAL_MS = 4000; // 4秒发送一次心跳
    private static final long HEARTBEAT_TIMEOUT_MS = 15000;  // 增加到10秒超时，提高容错性

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicReference<Instant> lastHeartbeatResponse = new AtomicReference<>(Instant.now());
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> heartbeatCheckTask;
    private String lastUsername;
    private String lastPassword;
    private boolean autoReconnect = true;
    private final AtomicBoolean cleanDisconnectInProgress = new AtomicBoolean(false); // 新增清理标记

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
                cleanDisconnect();
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
            return connectionState.get();
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

        if (reconnecting.get()) {
            logger.debug("重连正在进行中，跳过连接请求");
            return false;
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
            // 标记为正在重连，防止并发连接
            reconnecting.set(true);
            connectionState.set(ConnectionState.CONNECTING);

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

            // 配置心跳
            stompClient.setDefaultHeartbeat(new long[]{10000, 10000});

            // 尝试连接
            stompSession = stompClient.connect(wsUrl, new ClientSessionHandler())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 重置重连计数和状态
            reconnectAttempts.set(0);
            reconnecting.set(false);
            cleanDisconnectInProgress.set(false);
            connectionState.set(ConnectionState.CONNECTED);

            // 重新订阅
            resubscribeAll();

            // 发送连接消息
            sendConnectMessage();

            // 订阅心跳响应
            subscribeToHeartbeatResponses();

            // 启动心跳机制
            startHeartbeat();

            logger.info("WebSocket连接成功");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            connectionState.set(ConnectionState.DISCONNECTED);
            logger.error("WebSocket连接被中断: {}", e.getMessage());
            scheduleReconnect();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            connectionState.set(ConnectionState.DISCONNECTED);
            logger.error("WebSocket连接失败: {}", e.getMessage());
            scheduleReconnect();
            return false;
        } finally {
            reconnecting.set(false);
        }
    }

    /**
     * 断开WebSocket连接
     */
    @PreDestroy
    public synchronized void disconnect() {
        if (!isConnected()) {
            logger.debug("WebSocket未连接，无需断开");
            return;
        }

        // 停止心跳
        stopHeartbeat();

        try {
            if (stompSession != null && stompSession.isConnected()) {
                stompSession.disconnect();
                logger.info("WebSocket已断开连接");
            }
        } catch (Exception e) {
            logger.error("断开WebSocket连接时出错: {}", e.getMessage(), e);
        } finally {
            stompSession = null;
            connectionState.set(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * 清理断开连接 - 不触发重连
     */
    public synchronized void cleanDisconnect() {
        if (cleanDisconnectInProgress.compareAndSet(false, true)) {
            try {
                // 停止自动重连和心跳
                autoReconnect = false;
                stopHeartbeat();

                // 取消所有待处理的重连任务
                reconnecting.set(false);
                reconnectAttempts.set(0);

                // 断开连接
                if (stompSession != null) {
                    try {
                        if (stompSession.isConnected()) {
                            stompSession.disconnect();
                        }
                    } catch (Exception e) {
                        logger.warn("清理断开连接时出现异常: {}", e.getMessage());
                    } finally {
                        stompSession = null;
                    }
                }

                // 更新状态
                connectionState.set(ConnectionState.DISCONNECTED);
                logger.info("已执行清理断开连接");
            } finally {
                cleanDisconnectInProgress.set(false);
                // 恢复自动重连设置，为下次连接做准备
                autoReconnect = true;
            }
        } else {
            logger.debug("清理断开连接已在进行中");
        }
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
            try {
                doSubscribe(destination, payloadType, messageHandler);
            } catch (Exception e) {
                logger.error("订阅 {} 失败: {}", destination, e.getMessage());
            }
        }
    }

    /**
     * 执行实际订阅操作
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> void doSubscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        if (stompSession == null || !stompSession.isConnected()) {
            logger.warn("尝试订阅 {} 但STOMP会话未连接", destination);
            return;
        }

        try {
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
        } catch (Exception e) {
            logger.error("订阅 {} 时发生异常: {}", destination, e.getMessage());
            // 连接可能已断开，触发重连逻辑
            handleDisconnect();
        }
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
        int successCount = 0;

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
                logger.debug("重新订阅成功: {}", destination);
                successCount++;
            } catch (Exception e) {
                logger.error("重新订阅 {} 失败: {}", destination, e.getMessage());
            }
        }

        logger.info("成功重新订阅 {}/{} 个主题", successCount, subscriptions.size());
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
        payload.put("timestamp", System.currentTimeMillis());

        send("/app/user.connect", payload);
    }

    /**
     * 发送消息到指定目的地
     */
    public void send(String destination, Object payload) {
        if (!isConnected()) {
            logger.warn("WebSocket未连接，无法发送消息到: {}", destination);
            return;
        }

        try {
            stompSession.send(destination, payload);
        } catch (Exception e) {
            logger.error("发送消息到 {} 失败: {}", destination, e.getMessage());
            handleDisconnect(); // 触发重连逻辑
        }
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return stompSession != null && stompSession.isConnected() &&
                connectionState.get() == ConnectionState.CONNECTED;
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

    /**
     * 发送心跳消息到服务器
     */
    public void sendHeartbeat() {
        if (!isConnected()) {
            logger.warn("尝试发送心跳但WebSocket未连接");
            return;
        }

        try {
            Map<String, Object> heartbeatMessage = new HashMap<>();
            heartbeatMessage.put("timestamp", System.currentTimeMillis());

            send("/app/user.heartbeat", heartbeatMessage);
            logger.debug("心跳消息已发送");
        } catch (Exception e) {
            logger.error("发送心跳消息失败: {}", e.getMessage());
            // 如果发送失败，可能是连接已断开，触发重连逻辑
            handleDisconnect();
        }
    }

    /**
     * 订阅心跳响应消息
     */
    private void subscribeToHeartbeatResponses() {
        if (!isConnected()) {
            logger.warn("尝试订阅心跳响应但WebSocket未连接");
            return;
        }

        subscribe("/user/queue/heartbeat", Map.class, this::processHeartbeatResponse);
        logger.debug("已订阅心跳响应队列");
    }

    /**
     * 处理从服务器接收的心跳响应
     */
    private void processHeartbeatResponse(Map<String, Object> payload) {
        handleHeartbeatResponse();
        logger.debug("收到心跳响应: {}", payload);
    }

    /**
     * 启动心跳机制
     */
    public void startHeartbeat() {
        stopHeartbeat(); // 先停止现有心跳任务，避免重复

        // 设置最后一次心跳响应时间为当前时间
        lastHeartbeatResponse.set(Instant.now());

        // 定期发送心跳
        heartbeatTask = taskScheduler.scheduleWithFixedDelay(() -> {
            if (isConnected()) {
                sendHeartbeat();
            }
        }, HEARTBEAT_INTERVAL_MS);

        // 检查心跳响应
        heartbeatCheckTask = taskScheduler.scheduleWithFixedDelay(() -> {
            if (isConnected() &&
                    Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis() >
                            HEARTBEAT_TIMEOUT_MS + HEARTBEAT_INTERVAL_MS) {

                logger.warn("心跳超时，标记连接为不健康");
                connectionState.set(ConnectionState.UNHEALTHY);

                // 断开连接并重连，但不再这里直接调用disconnect
                // 而是通过handleDisconnect方法来处理，确保状态一致
                handleDisconnect();
            }
        }, HEARTBEAT_INTERVAL_MS);

        logger.info("心跳机制已启动");
    }

    /**
     * 停止心跳机制
     */
    public void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(true);
            heartbeatTask = null;
        }

        if (heartbeatCheckTask != null) {
            heartbeatCheckTask.cancel(true);
            heartbeatCheckTask = null;
        }
    }

    /**
     * 设置自动重连功能
     */
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    /**
     * 保存登录凭据用于自动重连
     */
    public void setLoginCredentials(String username, String password) {
        this.lastUsername = username;
        this.lastPassword = password;
    }

    /**
     * 处理连接断开
     */
    public void handleDisconnect() {
        // 避免重复处理
        if (connectionState.get() == ConnectionState.DISCONNECTED ||
                connectionState.get() == ConnectionState.CONNECTING) {
            return;
        }

        // 标记连接状态
        connectionState.set(ConnectionState.DISCONNECTED);

        // 停止心跳
        stopHeartbeat();

        // 安全地清理stompSession
        if (stompSession != null) {
            try {
                if (stompSession.isConnected()) {
                    stompSession.disconnect();
                }
            } catch (Exception e) {
                logger.debug("清理STOMP会话时出现异常: {}", e.getMessage());
            } finally {
                stompSession = null;
            }
        }

        // 如果启用了自动重连且有有效会话，尝试重连
        if (autoReconnect && sessionManager.hasValidSession()) {
            logger.info("检测到连接断开，准备重连...");
            synchronized (reconnectLock) {
                if (!reconnecting.get()) {
                    scheduleReconnect();
                } else {
                    logger.debug("重连已在计划中，跳过额外重连请求");
                }
            }
        } else {
            logger.info("连接已断开，未启用自动重连或无有效会话");
        }
    }

    /**
     * 处理心跳响应
     */
    public void handleHeartbeatResponse() {
        lastHeartbeatResponse.set(Instant.now());
        if (connectionState.get() == ConnectionState.UNHEALTHY) {
            connectionState.set(ConnectionState.CONNECTED);
            logger.info("心跳恢复，连接已恢复健康状态");
        }
    }

    /**
     * 安排重连任务，使用指数退避策略
     */
    private void scheduleReconnect() {
        synchronized (reconnectLock) {
            if (reconnecting.get() || !autoReconnect) {
                return;
            }

            int attempts = reconnectAttempts.incrementAndGet();
            if (attempts > MAX_RECONNECT_ATTEMPTS) {
                logger.error("已达到最大重连次数 ({})，停止重连", MAX_RECONNECT_ATTEMPTS);
                reconnecting.set(false);
                reconnectAttempts.set(0);

                // 会话可能已失效，通知用户需要重新登录
                eventPublisher.publishEvent(new ConnectionFailedEvent(this, "连接尝试失败，请重新登录"));
                return;
            }

            // 使用指数退避策略，但有最大延迟限制
            long delay = Math.min(
                    INITIAL_RECONNECT_DELAY_MS * (long)Math.pow(2, attempts - 1),
                    MAX_RECONNECT_DELAY_MS
            );

            logger.info("计划在 {}ms 后重连 (尝试 {}/{})", delay, attempts, MAX_RECONNECT_ATTEMPTS);

            reconnecting.set(true);
            connectionState.set(ConnectionState.CONNECTING);

            taskScheduler.schedule(() -> {
                try {
                    boolean connected = connect();
                    if (connected) {
                        // 重连成功，重置尝试次数
                        reconnectAttempts.set(0);
                        connectionState.set(ConnectionState.CONNECTED);
                        reconnecting.set(false);

                        // 通知重连成功
                        eventPublisher.publishEvent(new ReconnectSuccessEvent(this));
                    } else {
                        // 重连失败，继续尝试
                        synchronized (reconnectLock) {
                            reconnecting.set(false);
                            scheduleReconnect();
                        }
                    }
                } catch (Exception e) {
                    logger.error("重连时发生错误", e);
                    synchronized (reconnectLock) {
                        reconnecting.set(false);
                        scheduleReconnect();
                    }
                }
            }, new Date(System.currentTimeMillis() + delay));
        }
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
            handleDisconnect();
        }

        @Override
        public void handleTransportError(StompSession session, Throwable exception) {
            logger.error("WebSocket传输错误: {}", exception.getMessage());
            handleDisconnect();
        }

        public void connectionLost(StompSession session) {
            logger.warn("WebSocket连接丢失");
            handleDisconnect();
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