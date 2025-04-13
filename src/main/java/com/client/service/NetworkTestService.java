package com.client.service;

import com.client.network.ConnectionState;
import com.client.service.api.RoomApiService;
import com.client.service.api.UserApiService;
import com.client.session.SessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网络连接测试服务
 * 用于测试客户端与服务器的网络连接情况
 * 仅在客户端启动时执行一次测试
 */
@Service
@Profile("dev") // 仅在开发环境中启用
public class NetworkTestService implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(NetworkTestService.class);
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_TEST_WAIT_SECONDS = 30;

    private final SessionManager sessionManager;
    private final WebSocketService webSocketService;
    private final NetworkStatusService networkStatusService;
    private final UserApiService userApiService;
    private final RoomApiService roomApiService;
    private final TaskScheduler taskScheduler;

    @Value("${app.network.test.enabled:false}")
    private boolean networkTestEnabled;

    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);
    private final AtomicBoolean testRunning = new AtomicBoolean(false);
    private final AtomicBoolean loginAttempted = new AtomicBoolean(false);

    @Autowired
    public NetworkTestService(SessionManager sessionManager,
                              WebSocketService webSocketService,
                              NetworkStatusService networkStatusService,
                              UserApiService userApiService,
                              RoomApiService roomApiService,
                              TaskScheduler taskScheduler) {
        this.sessionManager = sessionManager;
        this.webSocketService = webSocketService;
        this.networkStatusService = networkStatusService;
        this.userApiService = userApiService;
        this.roomApiService = roomApiService;
        this.taskScheduler = taskScheduler;
    }

    /**
     * 在应用程序启动后运行，检查是否执行网络测试
     */
    @Override
    public void run(ApplicationArguments args) {
        // 检查是否启用网络测试
        if (!networkTestEnabled) {
            logger.info("网络测试未启用，跳过测试");
            return;
        }

        // 延迟执行测试，确保应用程序完全初始化
        executor.schedule(() -> {
            try {
                // 如果尚未登录，尝试登录
                if (!sessionManager.hasValidSession() && !loginAttempted.getAndSet(true)) {
                    logger.info("执行测试前尝试登录");
                    userApiService.login("testUser", "testPassword");

                    // 等待登录完成（给予2秒处理时间）
                    Thread.sleep(2000);
                }

                if (sessionManager.hasValidSession()) {
                    logger.info("客户端已登录，开始运行网络测试");
                    runNetworkTests();
                } else {
                    logger.info("客户端未登录，跳过网络测试");
                }
            } catch (Exception e) {
                logger.error("网络测试初始化失败", e);
            }
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * 运行所有网络测试
     */
    public void runNetworkTests() {
        if (testRunning.getAndSet(true)) {
            logger.info("测试已在运行中，跳过重复执行");
            return;
        }

        try {
            printTestHeader("开始网络连接测试套件");

            // 测试HTTP和WebSocket基本连接
            testHttpConnection();
            testWebSocketConnection();

            // 高级测试 - 只有当WebSocket连接成功时执行
            if (webSocketService.isConnected()) {
                testWebSocketSubscription();
                testMessageSending();
                testHeartbeat();
                testReconnection();
            } else {
                printTestWarning("WebSocket未连接，跳过高级WebSocket测试");
            }

            printTestHeader("网络连接测试套件完成");
        } catch (Exception e) {
            logger.error("网络测试过程中发生异常", e);
        } finally {
            testRunning.set(false);
        }
    }

    /**
     * 测试HTTP连接
     */
    private void testHttpConnection() {
        printTestHeader("HTTP连接测试");
        try {
            // 测试基本的API调用
            printTestStart("测试获取用户信息API");
            var user = userApiService.getCurrentUser();
            printTestSuccess("成功获取用户信息: " + user.getUsername());

            printTestStart("测试获取房间列表API");
            var rooms = roomApiService.getRoomList();
            printTestSuccess("成功获取房间列表，共有 " + rooms.size() + " 个房间");

            // 只有在确认已经有有效的session后才尝试获取网络信息
            if (sessionManager.hasValidSession()) {
                printTestStart("测试获取网络信息API");
                try {
                    var networkInfo = userApiService.getNetworkInfo();
                    printTestSuccess("成功获取网络信息：" + networkInfo);
                } catch (Exception e) {
                    // 检查错误类型，提供更具体的错误信息
                    if (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized")) {
                        printTestWarning("获取网络信息需要更高权限：" + e.getMessage());
                    } else {
                        printTestFailure("获取网络信息失败：" + e.getMessage());
                    }
                }
            } else {
                printTestWarning("用户未完全授权，跳过网络信息API测试");
            }
        } catch (Exception e) {
            printTestFailure("HTTP连接测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试WebSocket连接
     */
    private void testWebSocketConnection() {
        printTestHeader("WebSocket连接测试");

        printTestStart("检查WebSocket连接状态");
        ConnectionState state = webSocketService.getConnectionState();

        if (state == ConnectionState.CONNECTED) {
            printTestSuccess("WebSocket已成功连接");
            return;
        }

        printTestInfo("WebSocket当前状态: " + state);

        // 确保有有效的会话再尝试连接
        if (sessionManager.hasValidSession()) {
            printTestStart("尝试建立WebSocket连接");

            // 获取有效的会话ID
            String sessionId = sessionManager.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                printTestFailure("无法建立WebSocket连接：会话ID无效");
                return;
            }

            printTestInfo("使用会话ID连接: " + sessionId);

            // 尝试建立连接
            boolean connected = webSocketService.connect();

            if (connected) {
                printTestSuccess("成功建立WebSocket连接");
            } else {
                printTestFailure("无法建立WebSocket连接");

                // 提供更多连接失败的调试信息
                printTestInfo("连接失败原因可能是：会话无效或WebSocket服务未正确配置");
                printTestInfo("当前会话状态: " + (sessionManager.hasValidSession() ? "有效" : "无效"));
            }
        } else {
            printTestWarning("用户未登录或会话无效，跳过WebSocket连接测试");
        }
    }

    /**
     * 测试WebSocket订阅
     */
    private void testWebSocketSubscription() {
        printTestHeader("WebSocket订阅测试");

        AtomicBoolean receivedSystemMessage = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        printTestStart("订阅系统通知");
        webSocketService.subscribeSystemNotifications(message -> {
            printTestInfo("收到系统通知: " + message);
            receivedSystemMessage.set(true);
            latch.countDown();
        });

        printTestSuccess("订阅成功，等待消息...");

        try {
            boolean received = latch.await(10, TimeUnit.SECONDS);
            if (received) {
                printTestSuccess("成功收到系统通知");
            } else {
                printTestWarning("10秒内未收到系统通知（正常，除非系统主动发送）");
            }
        } catch (InterruptedException e) {
            printTestFailure("等待系统通知时中断: " + e.getMessage());
        }
    }

    /**
     * 测试发送消息
     */
    private void testMessageSending() {
        printTestHeader("消息发送测试");

        printTestStart("发送大厅消息");
        try {
            webSocketService.sendLobbyMessage("这是一条来自网络测试的消息 - " + LocalDateTime.now().format(formatter));
            printTestSuccess("消息发送成功");
        } catch (Exception e) {
            printTestFailure("发送消息失败: " + e.getMessage());
            return;
        }

        // 订阅自己发送的消息进行验证
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        webSocketService.subscribeLobbyMessages(message -> {
            if (message.getMessage() != null && message.getMessage().contains("网络测试")) {
                printTestInfo("收到自己发送的测试消息: " + message.getMessage());
                messageReceived.set(true);
                latch.countDown();
            }
        });

        try {
            boolean received = latch.await(5, TimeUnit.SECONDS);
            if (received) {
                printTestSuccess("成功接收到自己发送的消息");
            } else {
                printTestWarning("5秒内未收到自己发送的消息");
            }
        } catch (InterruptedException e) {
            printTestFailure("等待消息时中断: " + e.getMessage());
        }
    }

    /**
     * 测试心跳机制
     */
    private void testHeartbeat() {
        printTestHeader("心跳机制测试");

        AtomicInteger heartbeatCounter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        printTestStart("发送心跳包");
        try {
            webSocketService.sendHeartbeat();
            printTestSuccess("心跳包发送成功");
            printTestInfo("等待心跳响应...");

            // 通常心跳没有直接响应，所以这里测试是否继续保持连接状态
            executor.schedule(() -> {
                if (webSocketService.isConnected()) {
                    printTestSuccess("心跳后连接保持活跃状态");
                } else {
                    printTestFailure("心跳后连接已断开");
                }
                latch.countDown();
            }, 3, TimeUnit.SECONDS);

            latch.await(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            printTestFailure("心跳测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试重连机制
     */
    private void testReconnection() {
        printTestHeader("重连机制测试");

        if (!webSocketService.isConnected()) {
            printTestWarning("WebSocket未连接，跳过重连测试");
            return;
        }

        printTestStart("模拟WebSocket断开连接");
        AtomicBoolean reconnected = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        ConnectionState originalState = webSocketService.getConnectionState();
        printTestInfo("原始连接状态: " + originalState);

        try {
            // 手动断开连接
            webSocketService.disconnect();
            printTestSuccess("连接已手动断开");

            // 观察是否会自动重连
            executor.schedule(() -> {
                ConnectionState newState = webSocketService.getConnectionState();
                printTestInfo("断开5秒后的连接状态: " + newState);

                if (newState == ConnectionState.CONNECTED || newState == ConnectionState.CONNECTING) {
                    reconnected.set(true);
                }

                // 如果仍未连接，手动尝试连接一次
                if (newState != ConnectionState.CONNECTED) {
                    printTestInfo("尝试手动重连...");
                    boolean success = webSocketService.connect();
                    if (success) {
                        printTestSuccess("手动重连成功");
                        reconnected.set(true);
                    } else {
                        printTestFailure("手动重连失败");
                    }
                }

                latch.countDown();
            }, 5, TimeUnit.SECONDS);

            latch.await(MAX_TEST_WAIT_SECONDS, TimeUnit.SECONDS);

            if (reconnected.get()) {
                printTestSuccess("重连机制测试通过");
            } else {
                printTestFailure("重连机制测试失败");
            }

        } catch (Exception e) {
            printTestFailure("重连测试过程中出错: " + e.getMessage());
        }
    }

    // 辅助方法：打印测试信息
    private void printTestHeader(String title) {
        logger.info("\n========== {} ==========", title);
    }

    private void printTestStart(String message) {
        logger.info("▶ 开始: {}", message);
    }

    private void printTestSuccess(String message) {
        logger.info("✅ 成功: {}", message);
    }

    private void printTestFailure(String message) {
        logger.error("❌ 失败: {}", message);
    }

    private void printTestWarning(String message) {
        logger.warn("⚠ 警告: {}", message);
    }

    private void printTestInfo(String message) {
        logger.info("ℹ 信息: {}", message);
    }
}