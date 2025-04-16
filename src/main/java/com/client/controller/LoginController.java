package com.client.controller;

import com.client.config.AppProperties;
import com.client.exception.ApiException;
import com.client.service.NetworkStatusService;
import com.client.service.UserApiService;
import com.client.view.FxmlView;
import com.jfoenix.controls.JFXButton;
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

/**
 * 登录界面控制器
 * 处理用户登录、注册和服务器设置
 */
@Controller
public class LoginController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    @FXML private JFXTextField usernameField;
    @FXML private JFXPasswordField passwordField;
    @FXML private JFXButton loginButton;
    @FXML private JFXButton registerButton;
    @FXML private JFXButton settingsButton;
    @FXML private Label errorLabel;
    @FXML private Label connectionStatusLabel;

    @Autowired private UserApiService userApiService;
    @Autowired private NetworkStatusService networkStatusService;
    @Autowired private AppProperties appProperties;

    /**
     * 初始化控制器
     */
    @Override
    public void initialize() {
        logger.info("初始化登录控制器");

        initializeUI();
        setupEventHandlers();
        applyButtonStyles();
        updateConnectionStatus();
    }

    /**
     * 初始化UI组件状态
     */
    private void initializeUI() {
        // 初始化登录按钮状态
        loginButton.setDisable(false);
        loginButton.setText("登录");

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

        // 隐藏错误标签
        clearError();
    }

    /**
     * 设置事件处理器
     */
    private void setupEventHandlers() {
        // 设置按钮事件
        loginButton.setOnAction(event -> handleLogin());
        registerButton.setOnAction(event -> handleRegister());
        settingsButton.setOnAction(event -> handleSettings());

        // 添加输入监听器，清除错误提示
        usernameField.textProperty().addListener((obs, oldVal, newVal) -> clearError());
        passwordField.textProperty().addListener((obs, oldVal, newVal) -> clearError());

        // 处理回车键
        passwordField.setOnAction(event -> handleLogin());
        usernameField.setOnAction(event -> passwordField.requestFocus());
    }

    /**
     * 应用按钮样式
     */
    private void applyButtonStyles() {
        // 添加Material Design风格
        loginButton.getStyleClass().add("button-primary");
        registerButton.getStyleClass().add("button-secondary");

        // 添加悬停效果
        setupButtonHoverEffect(loginButton);
        setupButtonHoverEffect(registerButton);
        setupButtonHoverEffect(settingsButton);
    }

    /**
     * 设置按钮悬停效果
     */
    private void setupButtonHoverEffect(Button button) {
        button.setOnMouseEntered(e -> button.setOpacity(0.8));
        button.setOnMouseExited(e -> button.setOpacity(1.0));
    }

    /**
     * 更新连接状态信息
     */
    private void updateConnectionStatus() {
        String serverUrl = appProperties.getServerUrl();
        logger.info("当前连接到服务器: {}", serverUrl);
    }

    /**
     * 处理登录事件
     */
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

        executeAsync(() -> performAuthentication(username, password, true));
    }

    /**
     * 处理注册事件
     */
    private void handleRegister() {
        logger.debug("处理注册事件");
        clearError();

        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (!validateInputs(username, password)) {
            return;
        }

        registerButton.setDisable(true);
        executeAsync(() -> performAuthentication(username, password, false));
    }

    /**
     * 执行认证操作
     *
     * @param username 用户名
     * @param password 密码
     * @param isLogin 是否为登录操作
     */
    private void performAuthentication(String username, String password, boolean isLogin) {
        try {
            userApiService.login(username, password);

            Platform.runLater(() -> {
                logger.info("{}成功，用户: {}", isLogin ? "登录" : "注册", username);
                stageManager.switchScene(FxmlView.LOBBY);

                if (isLogin) {
                    loginButton.setDisable(false);
                    loginButton.setText("登录");
                } else {
                    registerButton.setDisable(false);
                }
            });
        } catch (ApiException e) {
            Platform.runLater(() -> {
                if (isLogin) {
                    showError(getFormattedErrorMessage(e));
                    loginButton.setDisable(false);
                    loginButton.setText("登录");
                } else {
                    showError("注册失败: " + e.getMessage());
                    registerButton.setDisable(false);
                }
            });
        } catch (Exception e) {
            Platform.runLater(() -> {
                showError("无法连接到服务器，请检查网络或服务器设置");
                logger.error("{}时发生错误", isLogin ? "登录" : "注册", e);

                if (isLogin) {
                    loginButton.setDisable(false);
                    loginButton.setText("登录");
                } else {
                    registerButton.setDisable(false);
                }
            });
        }
    }

    /**
     * 处理服务器设置事件
     */
    private void handleSettings() {
        logger.debug("打开服务器设置对话框");
        stageManager.openDialog(FxmlView.SERVER_SETTINGS);
        updateConnectionStatus();
    }

    /**
     * 格式化API异常消息为用户友好的提示
     */
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

    /**
     * 验证用户输入
     *
     * @return 输入是否有效
     */
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

    /**
     * 显示错误消息
     */
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }

    /**
     * 清除错误消息
     */
    private void clearError() {
        errorLabel.setText("");
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
    }
}