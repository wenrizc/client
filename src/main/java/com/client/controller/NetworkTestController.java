package com.client.controller;

import com.client.service.NetworkTestService;
import com.client.util.NetworkTestLogger;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 网络测试控制器 - 提供用户界面来执行网络测试
 */
@Component
@Profile("dev") // 仅在开发环境中启用
public class NetworkTestController {

    private static final Logger logger = LoggerFactory.getLogger(NetworkTestController.class);

    @FXML private Button runHttpTestButton;
    @FXML private Button runWsTestButton;
    @FXML private Button runFullTestButton;
    @FXML private TextArea resultsTextArea;
    @FXML private ScrollPane scrollPane;

    private final NetworkTestService networkTestService;

    @Autowired
    public NetworkTestController(NetworkTestService networkTestService) {
        this.networkTestService = networkTestService;
    }

    @FXML
    public void initialize() {
        // 设置自动滚动
        resultsTextArea.textProperty().addListener((observable, oldValue, newValue) -> {
            resultsTextArea.setScrollTop(Double.MAX_VALUE);
        });

        // 设置日志回调，将日志输出到UI
        NetworkTestLogger.setLogCallback(this::appendToTextArea);

        logMessage("网络测试工具已加载");
    }

    @FXML
    private void handleRunHttpTest() {
        logMessage("执行HTTP连接测试...");
        runHttpTestButton.setDisable(true);

        // 创建单独的线程运行测试，避免阻塞UI
        new Thread(() -> {
            try {
                networkTestService.runHttpTests();
            } catch (Exception e) {
                logError("HTTP测试出错: " + e.getMessage());
            } finally {
                Platform.runLater(() -> runHttpTestButton.setDisable(false));
            }
        }).start();
    }

    @FXML
    private void handleRunWsTest() {
        logMessage("执行WebSocket连接测试...");
        runWsTestButton.setDisable(true);

        new Thread(() -> {
            try {
                networkTestService.runWebSocketTests();
            } catch (Exception e) {
                logError("WebSocket测试出错: " + e.getMessage());
            } finally {
                Platform.runLater(() -> runWsTestButton.setDisable(false));
            }
        }).start();
    }

    @FXML
    private void handleRunFullTest() {
        logMessage("执行完整网络测试...");
        runFullTestButton.setDisable(true);

        new Thread(() -> {
            try {
                networkTestService.runNetworkTests();
                Platform.runLater(() -> logMessage("完整测试完成"));
            } catch (Exception e) {
                logError("网络测试出错: " + e.getMessage());
            } finally {
                Platform.runLater(() -> runFullTestButton.setDisable(false));
            }
        }).start();
    }

    private void logMessage(String message) {
        appendToTextArea(message);
    }

    private void logError(String message) {
        appendToTextArea("错误: " + message);
    }

    private void appendToTextArea(String message) {
        Platform.runLater(() -> {
            resultsTextArea.appendText(message + "\n");
        });
    }
}