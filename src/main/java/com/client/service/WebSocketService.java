package com.client.service;

import java.lang.reflect.Type;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import com.client.config.AppProperties;
import com.client.model.Message;
import com.client.model.Room;
import com.client.network.ConnectionState;
import com.client.session.ConnectionFailedEvent;
import com.client.session.ReconnectSuccessEvent;
import com.client.session.SessionManager;

import jakarta.annotation.PreDestroy;

@Service
public class WebSocketService {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketService.class);

    // 连接相关配置
    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;

    // 心跳相关配置
    private static final long HEARTBEAT_INTERVAL_MS = 10000;  // 10秒，与STOMP配置保持一致
    private static final long HEARTBEAT_TIMEOUT_MS = 30000;   // 30秒，更合理的超时时间

    // 连接健康检查
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1分钟检查一次连接健康状况

    // 依赖注入
    private final SessionManager sessionManager;
    private final WebSocketStompClient stompClient;
    private final AppProperties appProperties;
    private final TaskScheduler taskScheduler;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // 状态变量
    private volatile StompSession stompSession;
    private final ConcurrentHashMap<String, SubscriptionEntry<?>> subscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final ReentrantLock reconnectLock = new ReentrantLock();
    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicReference<Instant> lastHeartbeatResponse = new AtomicReference<>(Instant.now());
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> heartbeatCheckTask;
    private volatile ScheduledFuture<?> healthCheckTask;
    private String lastUsername;
    private String lastPassword;
    private String lastErrorMessage = "";
    private boolean autoReconnect = true;
    private final AtomicBoolean cleanDisconnectInProgress = new AtomicBoolean(false);
    private final AtomicInteger heartbeatFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveHeartbeatSuccesses = new AtomicInteger(0);

    @Autowired
    public WebSocketService(SessionManager sessionManager, WebSocketStompClient stompClient,
                            AppProperties appProperties, TaskScheduler taskScheduler) {
        this.sessionManager = sessionManager;
        this.stompClient = stompClient;
        this.appProperties = appProperties;
        this.taskScheduler = taskScheduler;

        this.sessionManager.sessionIdProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty()) connect();
            else cleanDisconnect();
        });

        if (sessionManager.getSessionId() != null && !sessionManager.getSessionId().isEmpty()) {
            connect();
        }
    }

    public ConnectionState getConnectionState() {
        if (stompSession != null && stompSession.isConnected()) {
            return connectionState.get();
        } else if (reconnecting.get()) {
            return ConnectionState.RECONNECTING;
        } else {
            return ConnectionState.DISCONNECTED;
        }
    }

    /**
     * 建立WebSocket连接
     */
    public synchronized boolean connect() {
        if (isConnected()) return true;
        if (reconnecting.get()) return false;
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
            reconnecting.set(true);
            connectionState.set(ConnectionState.CONNECTING);

            String wsUrl = appProperties.getWsServerUrl();
            if (wsUrl.startsWith("ws://")) wsUrl = "http://" + wsUrl.substring(5);
            else if (wsUrl.startsWith("wss://")) wsUrl = "https://" + wsUrl.substring(6);

            wsUrl = wsUrl + "?sessionId=" + sessionId;
            logger.info("连接WebSocket: {}", wsUrl);

            stompClient.setDefaultHeartbeat(new long[] { HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS });
            stompSession = stompClient.connect(wsUrl, new ClientSessionHandler())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            reconnectAttempts.set(0);
            reconnecting.set(false);
            cleanDisconnectInProgress.set(false);
            connectionState.set(ConnectionState.CONNECTED);
            heartbeatFailures.set(0);
            consecutiveHeartbeatSuccesses.set(0);

            resubscribeAll();
            sendConnectMessage();
            subscribeToHeartbeatResponses();
            startHeartbeat();
            startHealthCheck();

            logger.info("WebSocket连接成功");
            return true;
        } catch (InterruptedException e) {
            lastErrorMessage = e.getMessage();
            Thread.currentThread().interrupt();
            handleConnectionFailure("WebSocket连接被中断: " + e.getMessage());
            return false;
        } catch (ExecutionException | TimeoutException e) {
            lastErrorMessage = e.getMessage();
            handleConnectionFailure("WebSocket连接失败: " + e.getMessage());
            return false;
        } finally {
            if (!isConnected()) {
                reconnecting.set(false);
            }
        }
    }

    /**
     * 断开WebSocket连接
     */
    @PreDestroy
    public synchronized void disconnect() {
        stopHeartbeat();
        stopHealthCheck();
        try {
            if (stompSession != null && stompSession.isConnected()) {
                stompSession.disconnect();
                logger.info("WebSocket已断开连接");
            }
        } catch (Exception e) {
            logger.error("断开WebSocket连接时出错: {}", e.getMessage());
        } finally {
            stompSession = null;
            connectionState.set(ConnectionState.DISCONNECTED);
        }
    }

    /**
     * 清理断开连接（不自动重连）
     */
    public synchronized void cleanDisconnect() {
        if (cleanDisconnectInProgress.compareAndSet(false, true)) {
            try {
                autoReconnect = false;
                stopHeartbeat();
                stopHealthCheck();
                reconnecting.set(false);
                reconnectAttempts.set(0);

                if (stompSession != null && stompSession.isConnected()) {
                    try {
                        stompSession.disconnect();
                    } catch (Exception e) {
                        logger.warn("清理断开连接时出现异常: {}", e.getMessage());
                    }
                    stompSession = null;
                }

                connectionState.set(ConnectionState.DISCONNECTED);
            } finally {
                cleanDisconnectInProgress.set(false);
                autoReconnect = true;
            }
        }
    }

    /**
     * 订阅主题
     */
    public <T> void subscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        subscriptions.put(destination, new SubscriptionEntry<>(payloadType, messageHandler));
        if (isConnected()) {
            try {
                doSubscribe(destination, payloadType, messageHandler);
            } catch (Exception e) {
                logger.error("订阅 {} 失败: {}", destination, e.getMessage());
            }
        }
    }

    /**
     * 发送消息
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
            handleDisconnect();
        }
    }

    /**
     * 检查是否连接
     */
    public boolean isConnected() {
        return stompSession != null && stompSession.isConnected() &&
                connectionState.get() == ConnectionState.CONNECTED;
    }

    // 订阅方法
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

    // 发送方法
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
     * 发送心跳消息
     */
    public void sendHeartbeat() {
        if (!isConnected()) return;

        try {
            Map<String, Object> heartbeatMessage = new HashMap<>();
            heartbeatMessage.put("timestamp", System.currentTimeMillis());
            send("/app/user.heartbeat", heartbeatMessage);
        } catch (Exception e) {
            logger.warn("发送心跳失败: {}", e.getMessage());
            heartbeatFailures.incrementAndGet();
            updateConnectionStateBasedOnHealth();
            if (heartbeatFailures.get() > 3) { // 连续3次心跳失败则主动断开
                handleDisconnect();
            }
        }
    }

    /**
     * 启动心跳机制
     */
    public void startHeartbeat() {
        stopHeartbeat();
        lastHeartbeatResponse.set(Instant.now());

        heartbeatTask = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (isConnected()) sendHeartbeat();
            } catch (Exception e) {
                logger.error("心跳任务异常: {}", e.getMessage());
                heartbeatFailures.incrementAndGet();
                updateConnectionStateBasedOnHealth();
            }
        }, HEARTBEAT_INTERVAL_MS);

        heartbeatCheckTask = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (isConnected() && Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis()
                        > HEARTBEAT_TIMEOUT_MS) {
                    logger.warn("心跳超时，标记连接为不健康");
                    connectionState.set(ConnectionState.UNHEALTHY);
                    heartbeatFailures.incrementAndGet();
                    if (heartbeatFailures.get() > 3) { // 连续3次心跳失败则主动断开
                        handleDisconnect();
                    }
                }
            } catch (Exception e) {
                logger.error("心跳检查任务异常: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MS);
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
     * 启动健康检查
     */
    public void startHealthCheck() {
        stopHealthCheck();

        healthCheckTask = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (isConnected()) {
                    checkConnectionHealth();
                }
            } catch (Exception e) {
                logger.error("健康检查任务异常: {}", e.getMessage());
            }
        }, HEALTH_CHECK_INTERVAL_MS);
    }

    /**
     * 停止健康检查
     */
    public void stopHealthCheck() {
        if (healthCheckTask != null) {
            healthCheckTask.cancel(true);
            healthCheckTask = null;
        }
    }

    /**
     * 检查连接健康状态
     */
    private void checkConnectionHealth() {
        // 检查最近心跳响应时间
        long timeSinceLastHeartbeat = Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis();

        // 更新连接状态
        updateConnectionStateBasedOnHealth();

        // 主动测试连接 - 发送额外心跳
        if (timeSinceLastHeartbeat > HEARTBEAT_INTERVAL_MS * 2) {
            logger.info("执行主动连接健康检查 - 发送额外心跳");
            sendHeartbeat();
        }

        logger.debug("连接健康检查 - 状态: {}, 最近心跳响应: {}ms前",
                connectionState.get(),
                timeSinceLastHeartbeat);
    }

    /**
     * 根据心跳健康状况更新连接状态
     */
    private void updateConnectionStateBasedOnHealth() {
        int failures = heartbeatFailures.get();
        int successes = consecutiveHeartbeatSuccesses.get();

        // 只有当连接处于CONNECTED或UNHEALTHY状态时才更新
        ConnectionState currentState = connectionState.get();
        if (currentState != ConnectionState.CONNECTED && currentState != ConnectionState.UNHEALTHY) {
            return;
        }

        if (failures > 3) {
            connectionState.set(ConnectionState.UNHEALTHY);
        } else if (failures > 0) {
            connectionState.set(ConnectionState.UNHEALTHY);
        } else if (successes > 0) {
            connectionState.set(ConnectionState.CONNECTED);
        }
    }

    /**
     * 设置自动重连
     */
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect = autoReconnect;
    }

    /**
     * 设置登录凭证
     */
    public void setLoginCredentials(String username, String password) {
        this.lastUsername = username;
        this.lastPassword = password;
    }

    /**
     * 处理连接断开
     */
    public void handleDisconnect() {
        if (connectionState.get() == ConnectionState.DISCONNECTED ||
                connectionState.get() == ConnectionState.CONNECTING) {
            return;
        }

        connectionState.set(ConnectionState.DISCONNECTED);
        stopHeartbeat();
        stopHealthCheck();

        if (stompSession != null) {
            try {
                if (stompSession.isConnected()) stompSession.disconnect();
            } catch (Exception e) {
                logger.debug("清理STOMP会话时出现异常: {}", e.getMessage());
            } finally {
                stompSession = null;
            }
        }

        if (autoReconnect && sessionManager.hasValidSession()) {
            reconnectLock.lock();
            try {
                if (!reconnecting.get()) scheduleReconnect();
            } finally {
                reconnectLock.unlock();
            }
        }
    }

    /**
     * 处理心跳响应
     */
    public void handleHeartbeatResponse() {
        lastHeartbeatResponse.set(Instant.now());
        if (connectionState.get() == ConnectionState.UNHEALTHY) {
            connectionState.set(ConnectionState.CONNECTED);
        }
        heartbeatFailures.set(0);
        consecutiveHeartbeatSuccesses.incrementAndGet();
        updateConnectionStateBasedOnHealth();
    }

    /**
     * 获取上次错误消息
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    // 私有辅助方法
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T> void doSubscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        if (stompSession == null || !stompSession.isConnected()) return;

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
            handleDisconnect();
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void resubscribeAll() {
        if (!isConnected() || subscriptions.isEmpty()) return;

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
                successCount++;
            } catch (Exception e) {
                logger.error("重新订阅 {} 失败: {}", destination, e.getMessage());
            }
        }
        logger.info("成功重新订阅 {}/{} 个主题", successCount, subscriptions.size());
    }

    private void sendConnectMessage() {
        if (!isConnected() || sessionManager.getCurrentUser() == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CONNECT");
        payload.put("username", sessionManager.getCurrentUser().getUsername());
        payload.put("timestamp", System.currentTimeMillis());
        send("/app/user.connect", payload);
    }

    private void subscribeToHeartbeatResponses() {
        if (!isConnected()) return;
        subscribe("/user/queue/heartbeat", Map.class, this::processHeartbeatResponse);
    }

    private void processHeartbeatResponse(Map<String, Object> payload) {
        handleHeartbeatResponse();
    }

    /**
     * 调度重连
     */
    private void scheduleReconnect() {
        reconnectLock.lock();
        try {
            if (reconnecting.get() || !autoReconnect) return;

            int attempts = reconnectAttempts.incrementAndGet();
            if (attempts > MAX_RECONNECT_ATTEMPTS) {
                reconnecting.set(false);
                reconnectAttempts.set(0);
                eventPublisher.publishEvent(new ConnectionFailedEvent(this, "连接尝试失败，请重新登录"));
                return;
            }

            long delay = Math.min(
                    INITIAL_RECONNECT_DELAY_MS * (long) Math.pow(2, attempts - 1),
                    MAX_RECONNECT_DELAY_MS);

            logger.info("计划在 {}ms 后重连 (尝试 {}/{})", delay, attempts, MAX_RECONNECT_ATTEMPTS);
            reconnecting.set(true);
            connectionState.set(ConnectionState.RECONNECTING);

            taskScheduler.schedule(() -> {
                try {
                    boolean connected = connect();
                    reconnectLock.lock();
                    try {
                        if (connected) {
                            reconnectAttempts.set(0);
                            connectionState.set(ConnectionState.CONNECTED);
                            reconnecting.set(false);
                            eventPublisher.publishEvent(new ReconnectSuccessEvent(this));
                        } else {
                            reconnecting.set(false);
                            scheduleReconnect();
                        }
                    } finally {
                        reconnectLock.unlock();
                    }
                } catch (Exception e) {
                    logger.error("重连时发生错误", e);
                    reconnectLock.lock();
                    try {
                        reconnecting.set(false);
                        scheduleReconnect();
                    } finally {
                        reconnectLock.unlock();
                    }
                }
            }, new Date(System.currentTimeMillis() + delay));
        } finally {
            reconnectLock.unlock();
        }
    }

    private void handleConnectionFailure(String errorMessage) {
        connectionState.set(ConnectionState.FAILED);
        logger.error(errorMessage);
        scheduleReconnect();
    }

    // 内部类
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