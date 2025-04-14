package com.client.controller;

import com.client.config.AppProperties;
import com.client.network.ApiException;
import com.client.service.NetworkStatusService;
import com.client.service.api.UserApiService;
import com.client.session.SessionManager;
import com.client.view.FxmlView;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXCheckBox;
import com.jfoenix.controls.JFXPasswordField;
import com.jfoenix.controls.JFXTextField;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import java.util.prefs.Preferences;

@Controller
public class LoginController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);
    @FXML
    private JFXTextField usernameField;
    @FXML
    private JFXPasswordField passwordField;
    @FXML
    private JFXButton loginButton;
    @FXML
    private JFXButton registerButton;
    @FXML
    private JFXButton settingsButton;
    @FXML
    private Label errorLabel;
    @FXML
    private Label connectionStatusLabel;
    @Autowired
    private UserApiService userApiService;
    @Autowired
    private NetworkStatusService networkStatusService;
    @Autowired
    private AppProperties appProperties;

    @Override
    public void initialize() {
        logger.info("初始化登录控制器");

        // 绑定网络状态显示
        connectionStatusLabel.textProperty().bind(networkStatusService.statusMessageProperty());
        networkStatusService.connectedProperty().addListener((obs, oldVal, newVal) -> {
            connectionStatusLabel.getStyleClass().clear();
            connectionStatusLabel.getStyleClass().add("status-label");
            connectionStatusLabel.getStyleClass().add(newVal ? "connected" : "disconnected");
        });

        // 初始应用样式
        connectionStatusLabel.getStyleClass().clear();
        connectionStatusLabel.getStyleClass().add("status-label");
        connectionStatusLabel.getStyleClass().add(networkStatusService.isConnected() ? "connected" : "disconnected");

        // 设置按钮事件
        loginButton.setOnAction(event -> handleLogin());
        registerButton.setOnAction(event -> handleRegister());
        settingsButton.setOnAction(event -> handleSettings());

        // 添加输入监听器，清除错误提示
        usernameField.textProperty().addListener((obs, oldVal, newVal) -> clearError());
        passwordField.textProperty().addListener((obs, oldVal, newVal) -> clearError());

        // 处理回车键登录
        passwordField.setOnAction(event -> handleLogin());
        usernameField.setOnAction(event -> passwordField.requestFocus());

        // 初始化显示服务器地址
        updateConnectionStatus();

        // 应用样式
        applyButtonStyles();
    }

    private void applyButtonStyles() {
        // 添加Material Design风格
        loginButton.getStyleClass().add("button-primary");
        registerButton.getStyleClass().add("button-secondary");

        // 添加悬停效果
        setupButtonHoverEffect(loginButton);
        setupButtonHoverEffect(registerButton);
        setupButtonHoverEffect(settingsButton);
    }

    private void setupButtonHoverEffect(Button button) {
        button.setOnMouseEntered(e -> button.setOpacity(0.8));
        button.setOnMouseExited(e -> button.setOpacity(1.0));
    }

    private void handleLogin() {
        logger.debug("处理登录事件");
        clearError();

        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (!validateInputs(username, password)) {
            return;
        }

        loginButton.setDisable(true);
        loginButton.setText("登录中...");

        // 异步执行登录操作
        executeAsync(() -> {
            try {
                // 调用登录API
                userApiService.login(username, password);

                // 登录成功，跳转到大厅
                Platform.runLater(() -> {
                    logger.info("登录成功，用户: {}", username);
                    stageManager.switchScene(FxmlView.LOBBY);
                });
            } catch (ApiException e) {
                Platform.runLater(() -> {
                    String errorMsg = getFormattedErrorMessage(e);
                    showError(errorMsg);
                    loginButton.setDisable(false);
                    loginButton.setText("登录");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("无法连接到服务器，请检查网络或服务器设置");
                    logger.error("登录时发生错误", e);
                    loginButton.setDisable(false);
                    loginButton.setText("登录");
                });
            }
        });
    }

    // 添加更友好的错误信息格式化
    private String getFormattedErrorMessage(ApiException e) {
        String message = e.getMessage();
        if (message.contains("密码错误")) {
            return "密码错误，请重试";
        } else if (message.contains("用户名不存在")) {
            return "用户名不存在，请注册";
        } else if (message.contains("连接超时")) {
            return "连接服务器超时，请检查网络";
        }
        return "登录失败: " + message;
    }

    private void updateConnectionStatus() {
        String serverUrl = appProperties.getServerUrl();
        logger.info("当前连接到服务器: {}", serverUrl);
    }


    private void handleRegister() {
        logger.debug("处理注册事件");
        clearError();

        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (!validateInputs(username, password)) {
            return;
        }

        registerButton.setDisable(true);

        // 异步执行注册操作（实际上通过同一个API）
        executeAsync(() -> {
            try {
                // 在游戏大厅系统中，登录和注册使用同一个API端点
                userApiService.login(username, password);

                // 注册/登录成功，跳转到大厅
                Platform.runLater(() -> {
                    logger.info("注册成功，用户: {}", username);
                    stageManager.switchScene(FxmlView.LOBBY);
                });
            } catch (ApiException e) {
                Platform.runLater(() -> {
                    showError("注册失败: " + e.getMessage());
                    registerButton.setDisable(false);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showError("无法连接到服务器");
                    logger.error("注册时发生错误", e);
                    registerButton.setDisable(false);
                });
            }
        });
    }

    private void handleSettings() {
        logger.debug("打开服务器设置对话框");
        stageManager.openDialog(FxmlView.SERVER_SETTINGS);

        // 设置完成后更新连接状态
        updateConnectionStatus();
    }

    private boolean validateInputs(String username, String password) {
        if (username.isEmpty()) {
            showError("用户名不能为空");
            usernameField.requestFocus();
            return false;
        }

        if (username.length() < 3) {
            showError("用户名至少需要3个字符");
            usernameField.requestFocus();
            return false;
        }

        if (username.length() > 20) {
            showError("用户名不能超过20个字符");
            usernameField.requestFocus();
            return false;
        }

        if (!username.matches("[a-zA-Z0-9_\\u4e00-\\u9fa5]+")) {
            showError("用户名只能包含字母、数字、下划线或中文");
            usernameField.requestFocus();
            return false;
        }

        if (password.isEmpty()) {
            showError("密码不能为空");
            passwordField.requestFocus();
            return false;
        }

        if (password.length() < 4) {
            showError("密码至少需要4个字符");
            passwordField.requestFocus();
            return false;
        }

        return true;
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }

    private void clearError() {
        errorLabel.setText("");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
    }



    private void executeAsync(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }
}