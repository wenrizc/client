package com.client.service;

import com.client.config.AppProperties;
import com.client.network.ApiException;
import com.client.network.ConnectionState;
import com.client.service.api.UserApiService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 网络状态服务，监测HTTP和WebSocket连接
 */
@Service
public class NetworkStatusService {
    private static final Logger logger = LoggerFactory.getLogger(NetworkStatusService.class);

    private final UserApiService userApiService;
    private final WebSocketService webSocketService;
    private final AppProperties appProperties;

    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final StringProperty statusMessage = new SimpleStringProperty("未连接");
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public NetworkStatusService(UserApiService userApiService, WebSocketService webSocketService,
                                AppProperties appProperties) {
        this.userApiService = userApiService;
        this.webSocketService = webSocketService;
        this.appProperties = appProperties;

        // 启动定期检查
        scheduler.scheduleAtFixedRate(this::checkNetworkStatus, 0, 30, TimeUnit.SECONDS);
    }

    public void checkNetworkStatus() {
        boolean httpConnected = checkHttpConnection();
        boolean wsConnected = checkWebSocketConnection();

        Platform.runLater(() -> {
            boolean isConnected = httpConnected && wsConnected;
            connected.set(isConnected);

            if (isConnected) {
                statusMessage.set("已连接");
            } else if (!httpConnected) {
                statusMessage.set("服务器连接失败");
            } else {
                statusMessage.set("WebSocket连接失败");
            }
        });
    }

    private boolean checkHttpConnection() {
        try {
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
            logger.warn("HTTP连接检查异常: {}", e.getMessage());
            return false;
        }
    }


    /**
     * 检查服务器连接状态并更新状态属性
     */
    public void checkServerConnection() {
        String serverUrl = appProperties.getServerUrl();
        executeAsync(() -> {
            boolean isConnected = testConnection(serverUrl);
            Platform.runLater(() -> {
                connected.set(isConnected);
                statusMessage.set(isConnected ? "已连接" : "未连接");
            });
        });
    }

    /**
     * 测试与服务器的连接
     *
     * @param serverUrl 服务器URL
     * @return 连接是否成功
     */
    private boolean testConnection(String serverUrl) {
        try {
            URL url = new URL(serverUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000); // 3秒超时
            connection.setRequestMethod("HEAD");
            int responseCode = connection.getResponseCode();
            return responseCode >= 200 && responseCode < 400;
        } catch (Exception e) {
            logger.error("测试服务器连接失败: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 异步执行任务
     */
    private void executeAsync(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }

    private boolean checkWebSocketConnection() {
        return webSocketService.getConnectionState() == ConnectionState.CONNECTED;
    }

    public BooleanProperty connectedProperty() {
        return connected;
    }

    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public String getStatusMessage() {
        return statusMessage.get();
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}