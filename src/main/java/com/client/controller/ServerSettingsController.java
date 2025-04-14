package com.client.controller;

import com.client.config.AppProperties;
import com.client.service.NetworkStatusService;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.Map;

@Controller
public class ServerSettingsController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(ServerSettingsController.class);

    @FXML private JFXTextField serverUrlField;
    @FXML private JFXButton testConnectionButton;
    @FXML private JFXButton saveButton;
    @FXML private JFXButton cancelButton;
    @FXML private Label statusLabel;
    @FXML private Label statusDetailsLabel; // 新增：用于显示详细连接信息

    @Autowired private AppProperties appProperties;
    @Autowired private NetworkStatusService networkStatusService;

    private Stage dialogStage;

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    @Override
    public void initialize() {
        logger.info("初始化服务器设置控制器");

        // 显示当前服务器URL
        serverUrlField.setText(appProperties.getServerUrl());

        // 设置按钮事件
        testConnectionButton.setOnAction(event -> testConnection());
        saveButton.setOnAction(event -> saveSettings());
        cancelButton.setOnAction(event -> cancelSettings());

        // 添加输入监听，清除状态
        serverUrlField.textProperty().addListener((obs, oldVal, newVal) -> clearStatus());

        // 初始化状态详情标签
        if (statusDetailsLabel != null) {
            statusDetailsLabel.setVisible(false);
            statusDetailsLabel.setWrapText(true);
        }
    }

    private void testConnection() {
        String url = serverUrlField.getText().trim();
        if (!validateUrl(url)) {
            showStatus("无效的服务器URL", false);
            showStatusDetails("URL必须以http://或https://开头");
            return;
        }

        testConnectionButton.setDisable(true);
        testConnectionButton.setText("测试中...");
        showStatus("正在测试连接...", true);
        showStatusDetails("正在连接到服务器，请稍候...");

        executeAsync(() -> {
            Map<String, Object> result = checkServerConnection(url);
            boolean isConnected = (boolean) result.get("success");
            String message = (String) result.get("message");
            String details = (String) result.get("details");

            Platform.runLater(() -> {
                if (isConnected) {
                    showStatus("连接成功", true);
                    showStatusDetails(details);
                } else {
                    showStatus("连接失败", false);
                    showStatusDetails(message + ": " + details);
                }
                testConnectionButton.setDisable(false);
                testConnectionButton.setText("测试连接");
            });
        });
    }

    // 更新现有的checkServerConnection方法，返回更详细的连接信息
    private Map<String, Object> checkServerConnection(String serverUrl) {
        Map<String, Object> result = new HashMap<>();
        result.put("success", false);
        result.put("message", "未知错误");
        result.put("details", "");

        try {
            // 确保URL以斜杠结尾
            if (!serverUrl.endsWith("/")) {
                serverUrl += "/";
            }

            logger.debug("测试连接到: {}", serverUrl + "api/status");
            URL url = new URL(serverUrl + "api/status");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            long startTime = System.currentTimeMillis();
            int responseCode = connection.getResponseCode();
            long endTime = System.currentTimeMillis();
            long responseTime = endTime - startTime;

            logger.debug("服务器连接测试状态码: {}, 响应时间: {}ms", responseCode, responseTime);

            if (responseCode == 200) {
                result.put("success", true);
                result.put("message", "连接成功");
                result.put("details", "响应时间: " + responseTime + "ms, 服务器状态正常");
                return result;
            } else {
                result.put("message", "服务器返回错误状态码");
                result.put("details", "状态码: " + responseCode + ", 响应时间: " + responseTime + "ms");
                return result;
            }
        } catch (UnknownHostException e) {
            logger.warn("无法解析主机名: {}", e.getMessage());
            result.put("message", "无法连接到服务器");
            result.put("details", "找不到服务器或DNS解析失败");
            return result;
        } catch (SocketTimeoutException e) {
            logger.warn("连接超时: {}", e.getMessage());
            result.put("message", "连接超时");
            result.put("details", "服务器响应时间超过5秒");
            return result;
        } catch (MalformedURLException e) {
            logger.warn("URL格式错误: {}", e.getMessage());
            result.put("message", "URL格式错误");
            result.put("details", e.getMessage());
            return result;
        } catch (IOException e) {
            logger.warn("IO异常: {}", e.getMessage());
            result.put("message", "网络连接错误");
            result.put("details", e.getMessage());
            return result;
        } catch (Exception e) {
            logger.error("未知错误: {}", e.getMessage());
            result.put("message", "未知错误");
            result.put("details", e.getMessage());
            return result;
        }
    }

    private void saveSettings() {
        String url = serverUrlField.getText().trim();
        if (!validateUrl(url)) {
            showStatus("无效的服务器URL", false);
            showStatusDetails("URL必须以http://或https://开头");
            return;
        }

        // 保存新URL前先测试连接
        testConnectionButton.setDisable(true);
        showStatus("验证服务器连接...", true);

        executeAsync(() -> {
            Map<String, Object> result = checkServerConnection(url);
            boolean isConnected = (boolean) result.get("success");

            Platform.runLater(() -> {
                testConnectionButton.setDisable(false);

                if (isConnected) {
                    // 保存新URL
                    appProperties.setServerUrl(url);
                    appProperties.save();
                    logger.info("服务器URL已更新: {}", url);

                    // 更新网络状态服务
                    networkStatusService.checkServerConnection();

                    // 关闭对话框
                    dialogStage.close();
                } else {
                    String message = (String) result.get("message");
                    String details = (String) result.get("details");
                    showStatus("无法连接到服务器", false);
                    showStatusDetails(message + ": " + details + "\n是否仍要保存此URL？请点击\"保存\"按钮再次确认。");

                    // 修改保存按钮行为，下次点击将强制保存
                    saveButton.setOnAction(e -> forceSaveSettings());
                }
            });
        });
    }

    private void forceSaveSettings() {
        String url = serverUrlField.getText().trim();
        // 强制保存URL，不进行连接检查
        appProperties.setServerUrl(url);
        appProperties.save();
        logger.info("已强制保存服务器URL: {}", url);

        // 更新网络状态服务
        networkStatusService.checkServerConnection();

        // 关闭对话框
        dialogStage.close();
    }

    private void cancelSettings() {
        dialogStage.close();
    }

    private boolean validateUrl(String url) {
        if (url.isEmpty()) {
            return false;
        }

        // 简单URL格式验证
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private void showStatus(String message, boolean success) {
        statusLabel.setText(message);
        if (success) {
            statusLabel.getStyleClass().removeAll("error-label");
            statusLabel.getStyleClass().add("success-label");
        } else {
            statusLabel.getStyleClass().removeAll("success-label");
            statusLabel.getStyleClass().add("error-label");
        }
        statusLabel.setVisible(true);
    }

    private void showStatusDetails(String details) {
        if (statusDetailsLabel != null) {
            statusDetailsLabel.setText(details);
            statusDetailsLabel.setVisible(true);
        }
    }

    private void clearStatus() {
        statusLabel.setText("");
        statusLabel.setVisible(false);

        if (statusDetailsLabel != null) {
            statusDetailsLabel.setText("");
            statusDetailsLabel.setVisible(false);
        }

        // 重置保存按钮行为
        saveButton.setOnAction(event -> saveSettings());
    }
}