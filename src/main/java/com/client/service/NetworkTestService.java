package com.client.service;

import com.client.model.NetworkInfo;
import com.client.network.ApiException;
import com.client.network.ConnectionState;
import com.client.service.api.RoomApiService;
import com.client.service.api.UserApiService;
import com.client.session.SessionManager;
import com.client.util.NetworkTestLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 增强的网络连接测试服务
 * 提供详细的网络连接测试和诊断功能
 */
@Service
@Profile("dev") // 仅在开发环境中启用
public class NetworkTestService implements ApplicationRunner {

    private static final int MAX_TEST_WAIT_SECONDS = 30;
    private static final String HTTP_TEST = "http_test";
    private static final String WS_TEST = "ws_test";
    private static final String SUB_TEST = "sub_test";
    private static final String MSG_TEST = "msg_test";
    private static final String HB_TEST = "heartbeat_test";
    private static final String RECONNECT_TEST = "reconnect_test";

    private final SessionManager sessionManager;
    private final WebSocketService webSocketService;
    private final NetworkStatusService networkStatusService;
    private final UserApiService userApiService;
    private final RoomApiService roomApiService;
    private final TaskScheduler taskScheduler;

    @Value("${app.network.test.enabled:false}")
    private boolean networkTestEnabled;

    @Value("${app.server.url}")
    private String serverUrl;

    @Value("${app.ws.server.url}")
    private String wsServerUrl;

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
            NetworkTestLogger.info("网络测试未启用，跳过自动测试");
            return;
        }

        // 延迟执行测试，确保应用程序完全初始化
        executor.schedule(() -> {
            try {
                // 记录系统网络环境信息
                logSystemNetworkInfo();

                // 如果尚未登录，尝试登录
                if (!sessionManager.hasValidSession() && !loginAttempted.getAndSet(true)) {
                    NetworkTestLogger.info("执行测试前尝试登录");
                    userApiService.login("testUser", "testPassword");

                    // 等待登录完成（给予2秒处理时间）
                    Thread.sleep(2000);
                }

                if (sessionManager.hasValidSession()) {
                    NetworkTestLogger.info("客户端已登录，开始运行网络测试");
                    runNetworkTests();
                } else {
                    NetworkTestLogger.info("客户端未登录，跳过网络测试");
                }
            } catch (Exception e) {
                NetworkTestLogger.failure("auto_test", "网络测试初始化失败: " + e.getMessage());
            }
        }, 3, TimeUnit.SECONDS);
    }

    /**
     * 运行所有网络测试
     */
    public void runNetworkTests() {
        if (testRunning.getAndSet(true)) {
            NetworkTestLogger.warning("测试已在运行中，跳过重复执行");
            return;
        }

        try {
            NetworkTestLogger.header("开始网络连接测试套件");

            // 记录测试环境信息
            logTestEnvironmentInfo();

            // 测试HTTP和WebSocket基本连接
            testHttpConnection();
            testWebSocketConnection();

            // 高级测试 - 只有当WebSocket连接成功时执行
            if (webSocketService.isConnected()) {
                testWebSocketSubscription();
                testMessageSending();
                testHeartbeat();
                testReconnection();

                // 延迟测试，测试长连接稳定性
                testDelayedOperation();
            } else {
                NetworkTestLogger.warning("WebSocket未连接，跳过高级WebSocket测试");
            }

            NetworkTestLogger.header("网络连接测试套件完成");
        } catch (Exception e) {
            NetworkTestLogger.failure("full_test", "网络测试过程中发生异常: " + e.getMessage());
        } finally {
            testRunning.set(false);
        }
    }

    /**
     * 仅运行HTTP相关测试
     */
    public void runHttpTests() {
        if (testRunning.getAndSet(true)) {
            NetworkTestLogger.warning("测试已在运行中，跳过重复执行");
            return;
        }

        try {
            NetworkTestLogger.header("HTTP连接测试套件");
            logTestEnvironmentInfo();
            testHttpConnection();
            testHttpPerformance();
            NetworkTestLogger.header("HTTP测试套件完成");
        } catch (Exception e) {
            NetworkTestLogger.failure(HTTP_TEST, "HTTP测试异常: " + e.getMessage());
        } finally {
            testRunning.set(false);
        }
    }

    /**
     * 仅运行WebSocket相关测试
     */
    public void runWebSocketTests() {
        if (testRunning.getAndSet(true)) {
            NetworkTestLogger.warning("测试已在运行中，跳过重复执行");
            return;
        }

        try {
            NetworkTestLogger.header("WebSocket连接测试套件");
            logTestEnvironmentInfo();
            testWebSocketConnection();

            if (webSocketService.isConnected()) {
                testWebSocketSubscription();
                testMessageSending();
                testHeartbeat();
                testReconnection();
            } else {
                NetworkTestLogger.warning("WebSocket未连接，无法执行进一步测试");
            }

            NetworkTestLogger.header("WebSocket测试套件完成");
        } catch (Exception e) {
            NetworkTestLogger.failure(WS_TEST, "WebSocket测试异常: " + e.getMessage());
        } finally {
            testRunning.set(false);
        }
    }

    /**
     * 测试HTTP连接
     */
    private void testHttpConnection() {
        NetworkTestLogger.header("HTTP连接测试");

        try {
            // 测试基本的API调用
            NetworkTestLogger.start(HTTP_TEST, "测试获取用户信息API");
            long startTime = System.currentTimeMillis();
            int statusCode = 200;
            String username = "";

            try {
                var user = userApiService.getCurrentUser();
                username = user.getUsername();
                NetworkTestLogger.success(HTTP_TEST, "成功获取用户信息: " + username);
            } catch (ApiException e) {
                statusCode = e.getStatusCode();
                if (e.isUnauthorized()) {
                    NetworkTestLogger.warning("用户未授权，但HTTP连接正常");
                    statusCode = 401;
                } else {
                    throw e;
                }
            }

            long responseTime = System.currentTimeMillis() - startTime;
            NetworkTestLogger.metrics("获取用户信息", responseTime, statusCode);

            // 测试房间列表API
            NetworkTestLogger.start(HTTP_TEST, "测试获取房间列表API");
            startTime = System.currentTimeMillis();
            try {
                var rooms = roomApiService.getRoomList();
                NetworkTestLogger.success(HTTP_TEST, "成功获取房间列表，共有 " + rooms.size() + " 个房间");

                // 记录房间详情
                if (!rooms.isEmpty()) {
                    NetworkTestLogger.info("房间列表示例: " + rooms.get(0).getName() + " (ID: " + rooms.get(0).getId() + ")");
                }
            } catch (Exception e) {
                statusCode = extractStatusCode(e);
                NetworkTestLogger.failure(HTTP_TEST, "获取房间列表失败: " + e.getMessage());
            }

            responseTime = System.currentTimeMillis() - startTime;
            NetworkTestLogger.metrics("获取房间列表", responseTime, statusCode);

            // 只有在确认已经有有效的session后才尝试获取网络信息
            if (sessionManager.hasValidSession()) {
                NetworkTestLogger.start(HTTP_TEST, "测试获取网络信息API");
                startTime = System.currentTimeMillis();
                statusCode = 200;

                try {
                    var networkInfo = userApiService.getNetworkInfo();
                    logNetworkInfo(networkInfo);
                    NetworkTestLogger.success(HTTP_TEST, "成功获取网络信息");
                } catch (Exception e) {
                    statusCode = extractStatusCode(e);
                    // 检查错误类型，提供更具体的错误信息
                    if (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized")) {
                        NetworkTestLogger.warning("获取网络信息需要更高权限: " + e.getMessage());
                    } else {
                        NetworkTestLogger.failure(HTTP_TEST, "获取网络信息失败: " + e.getMessage());
                    }
                }

                responseTime = System.currentTimeMillis() - startTime;
                NetworkTestLogger.metrics("获取网络信息", responseTime, statusCode);
            } else {
                NetworkTestLogger.warning("用户未完全授权，跳过网络信息API测试");
            }
        } catch (Exception e) {
            NetworkTestLogger.failure(HTTP_TEST, "HTTP连接测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试HTTP性能
     */
    private void testHttpPerformance() {
        NetworkTestLogger.header("HTTP性能测试");

        // 测试API端点响应时间
        testApiEndpointPerformance("/api/status", "服务器状态");
        testApiEndpointPerformance("/api/public/health", "健康检查");

        // 测试多次请求平均响应时间
        testApiEndpointAveragePerformance("/api/status", "服务器状态", 5);
    }

    /**
     * 测试单个API端点性能
     */
    private void testApiEndpointPerformance(String endpoint, String description) {
        try {
            NetworkTestLogger.start(HTTP_TEST, "测试" + description + "API响应时间");
            String testUrl = serverUrl + (endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);

            URL url = new URL(testUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            long startTime = System.currentTimeMillis();
            int responseCode = connection.getResponseCode();
            long responseTime = System.currentTimeMillis() - startTime;

            if (responseCode >= 200 && responseCode < 300) {
                NetworkTestLogger.success(HTTP_TEST, description + "API响应成功");
            } else {
                NetworkTestLogger.warning(description + "API返回状态码: " + responseCode);
            }

            NetworkTestLogger.metrics(description + "API", responseTime, responseCode);
        } catch (Exception e) {
            NetworkTestLogger.failure(HTTP_TEST, description + "API测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试API端点平均响应时间
     */
    private void testApiEndpointAveragePerformance(String endpoint, String description, int count) {
        try {
            NetworkTestLogger.start(HTTP_TEST, "测试" + description + "API平均响应时间 (执行" + count + "次)");
            String testUrl = serverUrl + (endpoint.startsWith("/") ? endpoint.substring(1) : endpoint);

            long totalTime = 0;
            int successCount = 0;

            for (int i = 0; i < count; i++) {
                try {
                    URL url = new URL(testUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setConnectTimeout(3000);
                    connection.setReadTimeout(3000);

                    long startTime = System.currentTimeMillis();
                    int responseCode = connection.getResponseCode();
                    long responseTime = System.currentTimeMillis() - startTime;

                    if (responseCode >= 200 && responseCode < 300) {
                        totalTime += responseTime;
                        successCount++;
                    }

                    // 避免请求过快
                    Thread.sleep(200);
                } catch (Exception e) {
                    NetworkTestLogger.warning("请求" + (i+1) + "失败: " + e.getMessage());
                }
            }

            if (successCount > 0) {
                long averageTime = totalTime / successCount;
                NetworkTestLogger.success(HTTP_TEST, description + "API平均响应时间: " + averageTime + "ms (成功率: " + successCount + "/" + count + ")");
                NetworkTestLogger.metrics(description + "API平均响应", averageTime, 200);
            } else {
                NetworkTestLogger.failure(HTTP_TEST, description + "API全部请求失败");
            }
        } catch (Exception e) {
            NetworkTestLogger.failure(HTTP_TEST, description + "API测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试WebSocket连接
     */
    private void testWebSocketConnection() {
        NetworkTestLogger.header("WebSocket连接测试");

        NetworkTestLogger.start(WS_TEST, "检查WebSocket连接状态");
        ConnectionState state = webSocketService.getConnectionState();

        if (state == ConnectionState.CONNECTED) {
            NetworkTestLogger.success(WS_TEST, "WebSocket已成功连接");

            // 记录连接详情
            Map<String, Object> details = new HashMap<>();
            details.put("服务器URL", wsServerUrl);
            details.put("连接状态", state);
            details.put("Session ID", sessionManager.getSessionId());
            NetworkTestLogger.diagnostic("WebSocket连接详情", details);
            return;
        }

        NetworkTestLogger.info("WebSocket当前状态: " + state);

        // 确保有有效的会话再尝试连接
        if (sessionManager.hasValidSession()) {
            NetworkTestLogger.start(WS_TEST, "尝试建立WebSocket连接");

            // 获取有效的会话ID
            String sessionId = sessionManager.getSessionId();
            if (sessionId == null || sessionId.isEmpty()) {
                NetworkTestLogger.failure(WS_TEST, "无法建立WebSocket连接：会话ID无效");
                return;
            }

            NetworkTestLogger.info("使用会话ID连接: " + maskSessionId(sessionId));

            // 尝试建立连接
            boolean connected = webSocketService.connect();

            if (connected) {
                NetworkTestLogger.success(WS_TEST, "成功建立WebSocket连接");

                // 等待确保连接稳定
                try {
                    Thread.sleep(1000);
                    state = webSocketService.getConnectionState();
                    NetworkTestLogger.info("连接1秒后的WebSocket状态: " + state);
                } catch (InterruptedException ignored) {}
            } else {
                NetworkTestLogger.failure(WS_TEST, "无法建立WebSocket连接");

                // 提供更多连接失败的调试信息
                Map<String, Object> diagnosticInfo = new HashMap<>();
                diagnosticInfo.put("服务器URL", wsServerUrl);
                diagnosticInfo.put("会话状态", sessionManager.hasValidSession() ? "有效" : "无效");
                diagnosticInfo.put("连接状态", webSocketService.getConnectionState());
                diagnosticInfo.put("最后错误", webSocketService.getLastErrorMessage());
                NetworkTestLogger.diagnostic("WebSocket连接失败诊断", diagnosticInfo);
            }
        } else {
            NetworkTestLogger.warning("用户未登录或会话无效，跳过WebSocket连接测试");
        }
    }

    /**
     * 测试WebSocket订阅
     */
    private void testWebSocketSubscription() {
        NetworkTestLogger.header("WebSocket订阅测试");

        AtomicBoolean receivedSystemMessage = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        NetworkTestLogger.start(SUB_TEST, "订阅系统通知");
        webSocketService.subscribeSystemNotifications(message -> {
            NetworkTestLogger.info("收到系统通知: " + message);
            receivedSystemMessage.set(true);
            latch.countDown();
        });

        NetworkTestLogger.success(SUB_TEST, "订阅成功，等待消息...");

        try {
            boolean received = latch.await(10, TimeUnit.SECONDS);
            if (received) {
                NetworkTestLogger.success(SUB_TEST, "成功收到系统通知");
            } else {
                NetworkTestLogger.warning("10秒内未收到系统通知（正常，除非系统主动发送）");
            }
        } catch (InterruptedException e) {
            NetworkTestLogger.failure(SUB_TEST, "等待系统通知时中断: " + e.getMessage());
        }

        // 测试大厅消息订阅
        testLobbySubscription();
    }

    /**
     * 测试大厅消息订阅
     */
    private void testLobbySubscription() {
        NetworkTestLogger.start(SUB_TEST, "订阅大厅消息");
        AtomicBoolean subscribed = new AtomicBoolean(false);

        try {
            webSocketService.subscribeLobbyMessages(message -> {
                subscribed.set(true);
            });
            NetworkTestLogger.success(SUB_TEST, "大厅消息订阅成功");
        } catch (Exception e) {
            NetworkTestLogger.failure(SUB_TEST, "大厅消息订阅失败: " + e.getMessage());
        }
    }

    /**
     * 测试发送消息
     */
    private void testMessageSending() {
        NetworkTestLogger.header("消息发送测试");

        NetworkTestLogger.start(MSG_TEST, "发送大厅消息");
        String testMessage = "这是一条来自网络测试的消息 - " + System.currentTimeMillis();
        try {
            webSocketService.sendLobbyMessage(testMessage);
            NetworkTestLogger.success(MSG_TEST, "消息发送成功");
        } catch (Exception e) {
            NetworkTestLogger.failure(MSG_TEST, "发送消息失败: " + e.getMessage());
            return;
        }

        // 订阅自己发送的消息进行验证
        AtomicBoolean messageReceived = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        webSocketService.subscribeLobbyMessages(messageMap -> {
            String messageContent = (String) messageMap.get("message");
            if (messageContent != null && messageContent.contains("网络测试")) {
                NetworkTestLogger.info("收到自己发送的测试消息: " + messageContent);
                messageReceived.set(true);
                latch.countDown();
            }
        });

        try {
            boolean received = latch.await(5, TimeUnit.SECONDS);
            if (received) {
                NetworkTestLogger.success(MSG_TEST, "成功接收到自己发送的消息");
            } else {
                NetworkTestLogger.warning("5秒内未收到自己发送的消息");

                // 发送第二条消息尝试
                NetworkTestLogger.info("尝试发送第二条消息...");
                webSocketService.sendLobbyMessage("重试测试消息 - " + System.currentTimeMillis());
                // 等待2秒看是否能收到
                Thread.sleep(2000);
            }
        } catch (InterruptedException e) {
            NetworkTestLogger.failure(MSG_TEST, "等待消息时中断: " + e.getMessage());
        }
    }

    /**
     * 测试心跳机制
     */
    private void testHeartbeat() {
        NetworkTestLogger.header("心跳机制测试");

        AtomicInteger heartbeatCounter = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);

        NetworkTestLogger.start(HB_TEST, "发送心跳包");
        try {
            webSocketService.sendHeartbeat();
            NetworkTestLogger.success(HB_TEST, "心跳包发送成功");
            NetworkTestLogger.info("等待心跳响应...");

            // 通常心跳没有直接响应，所以这里测试是否继续保持连接状态
            executor.schedule(() -> {
                if (webSocketService.isConnected()) {
                    NetworkTestLogger.success(HB_TEST, "心跳后连接保持活跃状态");

                    // 测试多次心跳
                    NetworkTestLogger.info("测试连续3次心跳...");
                    for (int i = 0; i < 3; i++) {
                        try {
                            webSocketService.sendHeartbeat();
                            NetworkTestLogger.info("心跳 #" + (i+1) + " 发送成功");
                            Thread.sleep(1000);
                        } catch (Exception e) {
                            NetworkTestLogger.warning("心跳 #" + (i+1) + " 发送失败: " + e.getMessage());
                        }
                    }
                } else {
                    NetworkTestLogger.failure(HB_TEST, "心跳后连接已断开");
                }
                latch.countDown();
            }, 3, TimeUnit.SECONDS);

            latch.await(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            NetworkTestLogger.failure(HB_TEST, "心跳测试失败: " + e.getMessage());
        }
    }

    /**
     * 测试重连机制
     */
    private void testReconnection() {
        NetworkTestLogger.header("重连机制测试");

        if (!webSocketService.isConnected()) {
            NetworkTestLogger.warning("WebSocket未连接，跳过重连测试");
            return;
        }

        NetworkTestLogger.start(RECONNECT_TEST, "模拟WebSocket断开连接");
        AtomicBoolean reconnected = new AtomicBoolean(false);
        CountDownLatch latch = new CountDownLatch(1);

        ConnectionState originalState = webSocketService.getConnectionState();
        NetworkTestLogger.info("原始连接状态: " + originalState);

        try {
            // 手动断开连接
            webSocketService.disconnect();
            NetworkTestLogger.success(RECONNECT_TEST, "连接已手动断开");

            // 观察是否会自动重连
            executor.schedule(() -> {
                ConnectionState newState = webSocketService.getConnectionState();
                NetworkTestLogger.info("断开5秒后的连接状态: " + newState);

                if (newState == ConnectionState.CONNECTED || newState == ConnectionState.CONNECTING) {
                    reconnected.set(true);
                }

                // 如果仍未连接，手动尝试连接一次
                if (newState != ConnectionState.CONNECTED) {
                    NetworkTestLogger.info("尝试手动重连...");
                    boolean success = webSocketService.connect();
                    if (success) {
                        NetworkTestLogger.success(RECONNECT_TEST, "手动重连成功");
                        reconnected.set(true);
                    } else {
                        NetworkTestLogger.failure(RECONNECT_TEST, "手动重连失败");
                    }
                }

                latch.countDown();
            }, 5, TimeUnit.SECONDS);

            latch.await(MAX_TEST_WAIT_SECONDS, TimeUnit.SECONDS);

            if (reconnected.get()) {
                NetworkTestLogger.success(RECONNECT_TEST, "重连机制测试通过");
            } else {
                NetworkTestLogger.failure(RECONNECT_TEST, "重连机制测试失败");
            }

        } catch (Exception e) {
            NetworkTestLogger.failure(RECONNECT_TEST, "重连测试过程中出错: " + e.getMessage());
        }
    }

    /**
     * 测试延迟操作 - 检测长连接的稳定性
     */
    private void testDelayedOperation() {
        NetworkTestLogger.header("延迟操作测试");
        NetworkTestLogger.start("delay_test", "测试30秒后WebSocket状态");

        executor.schedule(() -> {
            ConnectionState state = webSocketService.getConnectionState();
            if (state == ConnectionState.CONNECTED) {
                NetworkTestLogger.success("delay_test", "30秒后WebSocket仍然保持连接");
                // 尝试发送心跳
                try {
                    webSocketService.sendHeartbeat();
                    NetworkTestLogger.success("delay_test", "30秒后心跳发送成功");
                } catch (Exception e) {
                    NetworkTestLogger.failure("delay_test", "30秒后心跳发送失败: " + e.getMessage());
                }
            } else {
                NetworkTestLogger.failure("delay_test", "30秒后WebSocket已断开，状态: " + state);
            }
        }, 30, TimeUnit.SECONDS);
    }

    /**
     * 记录系统网络环境信息
     */
    private void logSystemNetworkInfo() {
        try {
            Map<String, Object> sysInfo = new HashMap<>();

            // 获取本地主机名和IP
            InetAddress localHost = InetAddress.getLocalHost();
            sysInfo.put("主机名", localHost.getHostName());
            sysInfo.put("本地IP", localHost.getHostAddress());

            // 尝试获取Internet连接状态
            boolean hasInternet = isInternetAvailable();
            sysInfo.put("Internet连接", hasInternet ? "可用" : "不可用");

            // 尝试获取DNS
            InetAddress[] nameservers = InetAddress.getAllByName("www.baidu.com");
            if (nameservers.length > 0) {
                sysInfo.put("DNS解析", "正常");
                sysInfo.put("DNS示例", nameservers[0].getHostAddress());
            } else {
                sysInfo.put("DNS解析", "异常");
            }

            NetworkTestLogger.diagnostic("系统网络环境", sysInfo);
        } catch (Exception e) {
            NetworkTestLogger.warning("无法获取系统网络信息: " + e.getMessage());
        }
    }

    /**
     * 记录测试环境信息
     */
    private void logTestEnvironmentInfo() {
        Map<String, Object> envInfo = new HashMap<>();
        envInfo.put("HTTP服务器URL", serverUrl);
        envInfo.put("WebSocket服务器URL", wsServerUrl);
        envInfo.put("会话状态", sessionManager.hasValidSession() ? "有效" : "无效");
        envInfo.put("WebSocket状态", webSocketService.getConnectionState());
        NetworkTestLogger.diagnostic("测试环境信息", envInfo);
    }

    /**
     * 输出网络信息详细日志
     */
    private void logNetworkInfo(NetworkInfo info) {
        if (info == null) {
            NetworkTestLogger.warning("网络信息为空");
            return;
        }

        Map<String, Object> details = new HashMap<>();
        details.put("用户名", info.getUsername());
        details.put("虚拟IP", info.getVirtualIp());
        details.put("是否在房间中", info.isInRoom());
        details.put("房间ID", info.getRoomId());
        details.put("房间名称", info.getRoomName());
        details.put("网络ID", info.getNetworkId());
        details.put("网络名称", info.getNetworkName());
        details.put("网络类型", info.getNetworkType());

        NetworkTestLogger.diagnostic("网络信息详情", details);
    }

    /**
     * 检查是否有Internet连接
     */
    private boolean isInternetAvailable() {
        try {
            URL url = new URL("https://www.baidu.com");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.connect();
            return connection.getResponseCode() == 200;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从异常中提取HTTP状态码
     */
    private int extractStatusCode(Exception e) {
        if (e instanceof ApiException) {
            return ((ApiException) e).getStatusCode();
        }

        String message = e.getMessage();
        if (message != null) {
            if (message.contains("401")) return 401;
            if (message.contains("403")) return 403;
            if (message.contains("404")) return 404;
            if (message.contains("500")) return 500;
        }

        return 0;
    }

    /**
     * 对会话ID进行掩码处理，隐藏部分敏感信息
     */
    private String maskSessionId(String sessionId) {
        if (sessionId == null || sessionId.length() < 8) {
            return "***";
        }
        return sessionId.substring(0, 4) + "..." + sessionId.substring(sessionId.length() - 4);
    }
}