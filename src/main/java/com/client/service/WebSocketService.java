package com.client.service;

import com.client.config.AppProperties;
import com.client.model.Room;
import com.client.enums.ConnectionState;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * WebSocket服务
 * <p>
 * 提供WebSocket连接管理、消息收发、主题订阅以及连接健康监控
 * </p>
 */
@Service
public class WebSocketService {
    private static final Logger logger = LoggerFactory.getLogger(WebSocketService.class);

    private static final int CONNECT_TIMEOUT_SECONDS = 10;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long INITIAL_RECONNECT_DELAY_MS = 1000;
    private static final long MAX_RECONNECT_DELAY_MS = 30000;
    private static final long HEARTBEAT_INTERVAL_MS = 10000;  // 10秒发送一次心跳
    private static final long HEARTBEAT_TIMEOUT_MS = 30000;   // 30秒无响应判断为超时
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1分钟检查一次连接健康状况

    private final SessionManager sessionManager;
    private final WebSocketStompClient stompClient;
    private final AppProperties appProperties;
    private final TaskScheduler taskScheduler;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    private volatile StompSession stompSession;
    private final ConcurrentHashMap<String, SubscriptionEntry<?>> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, StompSession.Subscription> activeSubscriptions = new ConcurrentHashMap<>();
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    private final ReentrantLock reconnectLock = new ReentrantLock();
    private final AtomicReference<ConnectionState> connectionState = new AtomicReference<>(ConnectionState.DISCONNECTED);
    private final AtomicReference<Instant> lastHeartbeatResponse = new AtomicReference<>(Instant.now());
    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile ScheduledFuture<?> heartbeatCheckTask;
    private volatile ScheduledFuture<?> healthCheckTask;
    private String lastErrorMessage = "";
    private boolean autoReconnect = true;
    private final AtomicBoolean cleanDisconnectInProgress = new AtomicBoolean(false);
    private final AtomicInteger heartbeatFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveHeartbeatSuccesses = new AtomicInteger(0);

    /**
     * 构造函数
     */
    @Autowired
    public WebSocketService(SessionManager sessionManager, WebSocketStompClient stompClient,
                            AppProperties appProperties, TaskScheduler taskScheduler) {
        this.sessionManager = sessionManager;
        this.stompClient = stompClient;
        this.appProperties = appProperties;
        this.taskScheduler = taskScheduler;

        // 监听会话ID变化，自动连接或断开
        this.sessionManager.sessionIdProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null && !newValue.isEmpty()) connect();
            else cleanDisconnect();
        });

        // 如果已有会话ID，立即尝试连接
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
            return ConnectionState.RECONNECTING;
        } else {
            return ConnectionState.DISCONNECTED;
        }
    }

    /**
     * 建立WebSocket连接
     *
     * @return 连接是否成功
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

            // 构建WebSocket URL
            String wsUrl = appProperties.getWsServerUrl();
            if (wsUrl.startsWith("ws://")) wsUrl = "http://" + wsUrl.substring(5);
            else if (wsUrl.startsWith("wss://")) wsUrl = "https://" + wsUrl.substring(6);
            wsUrl = wsUrl + "?JSESSIONID=" + sessionId;

            logger.info("连接WebSocket: {}", wsUrl);

            // 设置心跳间隔并建立连接
            stompClient.setDefaultHeartbeat(new long[] { HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS });
            stompSession = stompClient.connect(wsUrl, new ClientSessionHandler())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            // 重置状态
            reconnectAttempts.set(0);
            reconnecting.set(false);
            cleanDisconnectInProgress.set(false);
            connectionState.set(ConnectionState.CONNECTED);
            heartbeatFailures.set(0);
            consecutiveHeartbeatSuccesses.set(0);

            // 初始化连接后操作
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
        } catch (Exception e) {
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
            activeSubscriptions.clear();
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
                activeSubscriptions.clear();
            } finally {
                cleanDisconnectInProgress.set(false);
                autoReconnect = true;
            }
        }
    }

    /**
     * 检查是否连接
     */
    public boolean isConnected() {
        return stompSession != null && stompSession.isConnected() &&
                connectionState.get() == ConnectionState.CONNECTED;
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
        activeSubscriptions.clear();

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
     * 处理连接失败
     */
    private void handleConnectionFailure(String errorMessage) {
        connectionState.set(ConnectionState.FAILED);
        logger.error(errorMessage);
        scheduleReconnect();
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

            // 使用指数退避策略计算延迟时间
            long delay = Math.min(
                    INITIAL_RECONNECT_DELAY_MS * (long) Math.pow(2, attempts - 1),
                    MAX_RECONNECT_DELAY_MS);

            logger.info("计划在 {}ms 后重连 (尝试 {}/{})", delay, attempts, MAX_RECONNECT_ATTEMPTS);
            reconnecting.set(true);
            connectionState.set(ConnectionState.RECONNECTING);

            // 调度重连任务
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

    /**
     * 获取上次错误消息
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }

    /**
     * 通用订阅方法
     */
    public <T> void subscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        subscriptions.put(destination, new SubscriptionEntry<>(payloadType, messageHandler));
        if (isConnected()) {
            try {
                doSubscribe(destination, payloadType, messageHandler);
            } catch (Exception e) {
                logger.error("订阅 {} 失败: {}", destination, e.getMessage());
                if (heartbeatCheckTask != null) {
                    heartbeatCheckTask.cancel(true);
                    checkConnectionHealth();
                }
            }
        }
    }

    /**
     * 执行订阅操作
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private <T> void doSubscribe(String destination, Class<T> payloadType, Consumer<T> messageHandler) {
        if (stompSession == null || !stompSession.isConnected()) {
            logger.warn("尝试在未连接状态下订阅 {}", destination);
            return;
        }

        // 检查心跳健康状态
        long timeSinceLastHeartbeat = Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis();
        if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
            logger.warn("订阅 {} 前检测到心跳超时 ({}ms)，标记连接为不健康", destination, timeSinceLastHeartbeat);
            connectionState.set(ConnectionState.UNHEALTHY);
            handleDisconnect();
            return;
        }

        try {
            StompSession.Subscription subscription = stompSession.subscribe(destination, new StompFrameHandler() {
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

            activeSubscriptions.put(destination, subscription);
        } catch (Exception e) {
            logger.error("订阅 {} 时发生异常: {}", destination, e.getMessage());
            handleDisconnect();
        }
    }

    /**
     * 重新订阅所有主题
     */
    @SuppressWarnings({ "unchecked", "rawtypes" })
    private void resubscribeAll() {
        if (!isConnected() || subscriptions.isEmpty()) return;

        int successCount = 0;
        for (Map.Entry<String, SubscriptionEntry<?>> entry : subscriptions.entrySet()) {
            String destination = entry.getKey();
            SubscriptionEntry<?> subscription = entry.getValue();

            try {
                StompSession.Subscription stompSubscription = stompSession.subscribe(destination, new StompFrameHandler() {
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

                activeSubscriptions.put(destination, stompSubscription);
                successCount++;
            } catch (Exception e) {
                logger.error("重新订阅 {} 失败: {}", destination, e.getMessage());
            }
        }

        logger.info("成功重新订阅 {}/{} 个主题", successCount, subscriptions.size());
    }

    /**
     * 取消订阅特定主题
     */
    public void unsubscribe(String destination) {
        if (stompSession != null && stompSession.isConnected()) {
            try {
                StompSession.Subscription subscription = activeSubscriptions.get(destination);
                if (subscription != null) {
                    subscription.unsubscribe();
                    activeSubscriptions.remove(destination);
                    logger.debug("已取消订阅: {}", destination);
                }
            } catch (Exception e) {
                logger.error("取消订阅 {} 失败: {}", destination, e.getMessage());
            }
        }
        subscriptions.remove(destination);
    }

    /**
     * 订阅大厅消息
     */
    public void subscribeLobbyMessages(Consumer<Map> messageHandler) {
        subscribe("/topic/lobby.messages", Map.class, messageHandler);
    }

    /**
     * 订阅房间消息
     */
    public void subscribeRoomMessages(Long roomId, Consumer<Map> messageHandler) {
        subscribe("/topic/room." + roomId + ".messages", Map.class, messageHandler);
    }

    /**
     * 订阅用户状态更新
     */
    public void subscribeUserStatus(Consumer<Map> statusHandler) {
        subscribe("/topic/users.status", Map.class, statusHandler);
    }

    /**
     * 订阅房间更新
     */
    public void subscribeRoomUpdates(Consumer<Map> updateHandler) {
        subscribe("/topic/rooms.updates", Map.class, updateHandler);
    }

    /**
     * 订阅个人消息
     */
    public void subscribePersonalMessages(Consumer<Map> messageHandler) {
        subscribe("/user/queue/messages", Map.class, messageHandler);
    }

    /**
     * 订阅错误消息
     */
    public void subscribeErrors(Consumer<Map> errorHandler) {
        subscribe("/user/queue/errors", Map.class, errorHandler);
    }

    /**
     * 订阅房间详情
     */
    public void subscribeRoomDetails(Consumer<Room> roomDetailHandler) {
        subscribe("/user/queue/room.detail", Room.class, roomDetailHandler);
    }

    /**
     * 订阅系统通知
     */
    public void subscribeSystemNotifications(Consumer<Map> notificationHandler) {
        subscribe("/topic/system.notifications", Map.class, notificationHandler);
    }

    /**
     * 订阅特定房间的所有相关消息
     */
    public void subscribeToRoom(Long roomId, Consumer<Map> messageHandler, Consumer<Map> updateHandler) {
        subscribe("/topic/room." + roomId + ".messages", Map.class, messageHandler);
        subscribe("/topic/room." + roomId + ".updates", Map.class, updateHandler);
    }

    /**
     * 取消订阅所有系统通知
     */
    public void unsubscribeFromSystemNotifications() {
        unsubscribe("/topic/system.notifications");
        unsubscribe("/topic/users.status");
        unsubscribe("/topic/lobby.messages");
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
            handleDisconnect();
        }
    }

    /**
     * 发送用户连接消息
     */
    private void sendConnectMessage() {
        if (!isConnected() || sessionManager.getCurrentUser() == null) return;

        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "CONNECT");
        payload.put("username", sessionManager.getCurrentUser().getUsername());
        payload.put("timestamp", System.currentTimeMillis());
        send("/app/user.connect", payload);
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
            if (heartbeatFailures.get() > 3) {
                handleDisconnect();
            }
        }
    }

    /**
     * 启动心跳机制
     */
    public void startHeartbeat() {
        stopHeartbeat();

        // 定期发送心跳
        heartbeatTask = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (isConnected()) {
                    sendHeartbeat();
                }
            } catch (Exception e) {
                logger.error("心跳发送任务异常: {}", e.getMessage());
            }
        }, HEARTBEAT_INTERVAL_MS);

        // 检查心跳响应
        heartbeatCheckTask = taskScheduler.scheduleWithFixedDelay(() -> {
            try {
                if (isConnected()) {
                    long timeSinceLastHeartbeat = Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis();
                    if (timeSinceLastHeartbeat > HEARTBEAT_TIMEOUT_MS) {
                        logger.warn("心跳超时: 最后一次响应在 {}ms 前", timeSinceLastHeartbeat);
                        heartbeatFailures.incrementAndGet();
                        if (heartbeatFailures.get() > 3) {
                            handleDisconnect();
                        }
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
        long timeSinceLastHeartbeat = Duration.between(lastHeartbeatResponse.get(), Instant.now()).toMillis();
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
     * 订阅心跳响应
     */
    private void subscribeToHeartbeatResponses() {
        if (!isConnected()) return;
        subscribe("/user/queue/heartbeat", Map.class, this::processHeartbeatResponse);
    }

    /**
     * 处理心跳响应消息
     */
    private void processHeartbeatResponse(Map<String, Object> payload) {
        handleHeartbeatResponse();
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
     * 订阅条目
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