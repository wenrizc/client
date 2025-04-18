package com.client.service;

import com.client.exception.ApiException;
import com.client.model.NetworkInfo;
import com.client.util.SessionManager;
import com.client.util.n2n.N2NClientManager;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 虚拟网络服务
 * <p>
 * 管理虚拟网络连接、监控和重连，作为N2N客户端管理器和API服务之间的协调者
 * </p>
 */
@Service
public class VirtualNetworkService {
    private static final Logger logger = LoggerFactory.getLogger(VirtualNetworkService.class);

    private final UserApiService userApiService;
    private final N2NClientManager n2nClientManager;
    private final SessionManager sessionManager;

    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final StringProperty statusMessage = new SimpleStringProperty("未连接");
    private final AtomicBoolean autoReconnect = new AtomicBoolean(true);
    private NetworkInfo currentNetworkInfo;

    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor();

    @Autowired
    public VirtualNetworkService(UserApiService userApiService,
                                 N2NClientManager n2nClientManager,
                                 SessionManager sessionManager) {
        this.userApiService = userApiService;
        this.n2nClientManager = n2nClientManager;
        this.sessionManager = sessionManager;

        // 监听会话变更，自动连接或断开连接
        this.sessionManager.addSessionChangeListener(sessionId -> {
            if (sessionId != null) {
                connectToNetwork();
            } else {
                disconnectFromNetwork();
            }
        });

        // 启动定期检查，每60秒检查一次连接状态，如果未连接则尝试重连
        reconnectScheduler.scheduleAtFixedRate(() -> {
            if (autoReconnect.get() && sessionManager.hasValidSession() && !n2nClientManager.isRunning()) {
                logger.info("检测到虚拟网络断开，尝试重新连接...");
                connectToNetwork();
            }
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 连接到虚拟网络
     *
     * @return 是否成功连接的Future
     */
    public CompletableFuture<Boolean> connectToNetwork() {
        if (!sessionManager.hasValidSession()) {
            logger.warn("尝试连接虚拟网络但没有有效会话");
            updateStatus(false, "未登录，无法连接");
            return CompletableFuture.completedFuture(false);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                logger.info("获取网络信息...");
                NetworkInfo networkInfo = userApiService.getNetworkInfo();

                if (networkInfo == null || networkInfo.getNetworkId() == null) {
                    logger.warn("未获取到有效的网络信息");
                    updateStatus(false, "未获取到网络信息");
                    return false;
                }

                this.currentNetworkInfo = networkInfo;

                logger.info("连接到虚拟网络: {}, 类型: {}",
                        networkInfo.getNetworkName(),
                        networkInfo.getNetworkType());

                boolean success = n2nClientManager.startN2NClient(networkInfo);
                updateStatus(success, success ? "已连接到虚拟网络" : "虚拟网络连接失败");

                return success;

            } catch (ApiException e) {
                logger.error("获取网络信息失败: {}", e.getMessage());
                updateStatus(false, "获取网络信息失败: " + e.getMessage());
                return false;
            } catch (Exception e) {
                logger.error("连接到虚拟网络时发生错误", e);
                updateStatus(false, "连接失败: " + e.getMessage());
                return false;
            }
        });
    }

    /**
     * 断开虚拟网络连接
     */
    public void disconnectFromNetwork() {
        logger.info("断开与虚拟网络的连接");

        // 先停止重连任务
        autoReconnect.set(false);

        // 清除当前网络信息
        currentNetworkInfo = null;

        // 更新UI状态
        Platform.runLater(() -> {
            connected.set(false);
            statusMessage.set("已断开连接");
        });

        logger.info("已断开与虚拟网络的连接");
    }

    /**
     * 获取当前网络信息
     *
     * @return 当前网络信息
     */
    public NetworkInfo getCurrentNetworkInfo() {
        return currentNetworkInfo;
    }

    /**
     * 刷新网络信息并更新连接
     *
     * @return 是否成功刷新的Future
     */
    public CompletableFuture<Boolean> refreshNetworkConnection() {
        return connectToNetwork();
    }

    /**
     * 检查是否已连接到虚拟网络
     *
     * @return 是否已连接
     */
    public boolean isConnected() {
        return n2nClientManager.isRunning();
    }

    /**
     * 设置是否自动重连
     *
     * @param autoReconnect 是否自动重连
     */
    public void setAutoReconnect(boolean autoReconnect) {
        this.autoReconnect.set(autoReconnect);
    }

    /**
     * 更新状态属性
     */
    private void updateStatus(boolean connected, String message) {
        Platform.runLater(() -> {
            this.connected.set(connected);
            this.statusMessage.set(message);
        });
    }

    /**
     * 获取连接状态属性
     */
    public BooleanProperty connectedProperty() {
        return connected;
    }

    /**
     * 获取连接状态
     */
    public boolean getConnected() {
        return connected.get();
    }

    /**
     * 获取状态消息属性
     */
    public StringProperty statusMessageProperty() {
        return statusMessage;
    }

    /**
     * 获取状态消息
     */
    public String getStatusMessage() {
        return statusMessage.get();
    }
}