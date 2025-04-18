package com.client.service;

import com.client.config.AppProperties;
import com.client.enums.ConnectionState;
import com.client.exception.ApiException;
import com.client.util.n2n.N2NClientManager;
import jakarta.annotation.Resource;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网络状态服务
 * <p>
 * 负责监控服务器连接状态，包括HTTP API和WebSocket连接，
 * 并通过JavaFX属性机制向UI提供实时状态更新。
 * </p>
 */
@Service
public class NetworkStatusService {
    private static final Logger logger = LoggerFactory.getLogger(NetworkStatusService.class);

    private final UserApiService userApiService;
    private final WebSocketService webSocketService;
    private final AppProperties appProperties;
    private final VirtualNetworkService virtualNetworkService;
    private final N2NClientManager n2nClientManager;


    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final BooleanProperty n2nConnected = new SimpleBooleanProperty(false);
    private final StringProperty statusMessage = new SimpleStringProperty("未连接");
    private final StringProperty n2nStatusMessage = new SimpleStringProperty("虚拟网络未连接");


    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Resource
    private final ThreadPoolTaskExecutor taskExecutor;

    /**
     * 创建网络状态服务
     *
     * @param userApiService API服务
     * @param webSocketService WebSocket服务
     * @param appProperties 应用程序配置
     */
    @Autowired
    public NetworkStatusService(UserApiService userApiService, WebSocketService webSocketService,
                                AppProperties appProperties, ThreadPoolTaskExecutor taskExecutor, VirtualNetworkService virtualNetworkService,
                                N2NClientManager n2nClientManager) {
        this.userApiService = userApiService;
        this.webSocketService = webSocketService;
        this.appProperties = appProperties;
        this.taskExecutor = taskExecutor;
        this.virtualNetworkService = virtualNetworkService;
        this.n2nClientManager = n2nClientManager;

        // 启动定期检查，每30秒检查一次
        scheduler.scheduleAtFixedRate(this::checkNetworkStatus, 0, 30, TimeUnit.SECONDS);

        // 监听虚拟网络状态变化
        virtualNetworkService.connectedProperty().addListener((obs, oldVal, newVal) -> {
            Platform.runLater(() -> {
                n2nConnected.set(newVal);
                n2nStatusMessage.set(virtualNetworkService.getStatusMessage());
            });
        });
    }

    /**
     * 检查服务器连接状态
     * <p>
     * 主动触发一次连接检查并更新状态
     * </p>
     */
    public void checkServerConnection() {
        checkNetworkStatus();
    }

    /**
     * 检查网络连接状态
     * <p>
     * 同时检查HTTP和WebSocket连接，更新状态属性
     * </p>
     */
    public void checkNetworkStatus() {
        executeAsync(() -> {
            boolean httpConnected = checkHttpConnection();
            boolean wsConnected = checkWebSocketConnection();
            boolean n2nConnected = virtualNetworkService.isConnected();

            Platform.runLater(() -> {
                boolean isConnected = httpConnected && wsConnected;
                connected.set(isConnected);
                this.n2nConnected.set(n2nConnected);

                if (isConnected) {
                    statusMessage.set("已连接");
                } else if (!httpConnected) {
                    statusMessage.set("服务器连接失败");
                } else {
                    statusMessage.set("WebSocket连接失败");
                }

                n2nStatusMessage.set(virtualNetworkService.getStatusMessage());
            });
        });
    }

    /**
     * 停止监控
     */
    public void stopMonitoring() {
        logger.info("停止网络状态监控");
        // 停止监控线程
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
                logger.debug("网络状态监控调度器已关闭");
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
                logger.warn("停止监控线程被中断", e);
            }
        }
        // 重置状态标志
        n2nConnected.set(false);
        n2nStatusMessage.set("未连接");
    }

    /**
     * 检查HTTP连接状态
     *
     * @return 连接是否正常
     */
    private boolean checkHttpConnection() {
        try {
            // 尝试调用API
            userApiService.getCurrentUser();
            return true;
        } catch (ApiException e) {
            if (e.isUnauthorized()) {
                // 401错误表示服务器正常，但用户未授权
                return true;
            }
            logger.warn("HTTP连接检查失败: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            // 如果API调用失败，尝试直接连接服务器
            logger.warn("API连接检查异常: {}", e.getMessage());
            return testDirectConnection();
        }
    }

    /**
     * 测试直接HTTP连接
     *
     * @return 连接是否成功
     */
    private boolean testDirectConnection() {
        String serverUrl = appProperties.getServerUrl();
        try {
            URL url = new URL(serverUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000); // 3秒超时
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            logger.error("直接HTTP连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 检查WebSocket连接状态
     *
     * @return 连接是否正常
     */
    private boolean checkWebSocketConnection() {
        return webSocketService.getConnectionState() == ConnectionState.CONNECTED;
    }

    /**
     * 异步执行任务
     */
    private void executeAsync(Runnable task) {
        taskExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("执行异步任务时出错", e);
            }
        });
    }

    // 属性访问器

    /**
     * 获取连接状态属性，用于UI绑定
     */
    public BooleanProperty connectedProperty() {
        return connected;
    }

    /**
     * 获取状态消息属性，用于UI绑定
     */
    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    /**
     * 获取当前连接状态
     *
     * @return 是否已连接
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * 获取虚拟网络连接状态属性，用于UI绑定
     */
    public BooleanProperty n2nConnectedProperty() {
        return n2nConnected;
    }

    /**
     * 获取当前虚拟网络连接状态
     *
     * @return 是否已连接
     */
    public boolean isN2nConnected() {
        return n2nConnected.get();
    }

    /**
     * 获取虚拟网络状态消息属性，用于UI绑定
     */
    public StringProperty n2nStatusMessageProperty() {
        return n2nStatusMessage;
    }

    /**
     * 获取当前虚拟网络状态消息
     *
     * @return 虚拟网络状态消息
     */
    public String getN2nStatusMessage() {
        return n2nStatusMessage.get();
    }

    /**
     * 获取当前状态消息
     *
     * @return 状态消息
     */
    public String getStatusMessage() {
        return statusMessage.get();
    }

    /**
     * 关闭服务，停止定时检查
     */
    public void shutdown() {
        scheduler.shutdownNow();
    }


    /**
     * 获取N2N详细状态信息
     *
     * @return N2N状态信息对象
     */
    public N2NClientManager.N2NStatusInfo getN2nDetailedStatus() {
        return n2nClientManager.getStatusInfo();
    }

    /**
     * 获取N2N详细日志
     *
     * @param errors 是否获取错误日志
     * @return 日志内容列表
     */
    public List<String> getN2nLogs(boolean errors) {
        return n2nClientManager.getLogs(errors);
    }

    /**
     * 启动N2N状态监控线程
     */
    private void startN2nMonitoring() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            try {
                boolean connected = n2nClientManager.isRunning();
                Platform.runLater(() -> {
                    n2nConnected.set(connected);
                    if (connected) {
                        n2nStatusMessage.set("已连接 - 网络ID: " + n2nClientManager.getCurrentNetworkId());
                    } else {
                        n2nStatusMessage.set("未连接");
                    }
                });
            } catch (Exception e) {
                logger.error("监控N2N状态时出错", e);
            }
        }, 0, 10, TimeUnit.SECONDS);
    }
}