package com.client.ui;

import com.client.Main;
import com.client.config.AppConfig;
import com.client.model.User;
import com.client.service.ApiService;
import com.client.util.AlertUtil;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

public class LoginView extends VBox {

    private final TextField usernameField;
    private final PasswordField passwordField;
    private final Button loginButton;
    private final AppConfig appConfig;
    private final ApiService apiService;

    public LoginView() {
        appConfig = new AppConfig();
        apiService = new ApiService(appConfig);

        setPadding(new Insets(20));
        setSpacing(10);
        setAlignment(Pos.CENTER);

        Label titleLabel = new Label("游戏大厅 - 登录");
        titleLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold;");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setAlignment(Pos.CENTER);

        // 用户名输入
        Label usernameLabel = new Label("用户名:");
        usernameField = new TextField();
        usernameField.setPromptText("请输入用户名");
        grid.add(usernameLabel, 0, 0);
        grid.add(usernameField, 1, 0);

        // 密码输入
        Label passwordLabel = new Label("密码:");
        passwordField = new PasswordField();
        passwordField.setPromptText("请输入密码");
        grid.add(passwordLabel, 0, 1);
        grid.add(passwordField, 1, 1);

        // 登录按钮
        loginButton = new Button("登录/注册");
        loginButton.setDefaultButton(true);
        loginButton.setOnAction(e -> handleLogin());

        // 服务器设置按钮
        Button settingsButton = new Button("设置");
        settingsButton.setOnAction(e -> showSettingsDialog());

        HBox buttonBox = new HBox(10, loginButton, settingsButton);
        buttonBox.setAlignment(Pos.CENTER);

        getChildren().addAll(titleLabel, grid, buttonBox);
    }

    private void handleLogin() {
        String username = usernameField.getText().trim();
        String password = passwordField.getText();

        if (username.isEmpty()) {
            AlertUtil.showWarning("输入错误", "请输入用户名");
            return;
        }

        if (password.isEmpty()) {
            AlertUtil.showWarning("输入错误", "请输入密码");
            return;
        }

        loginButton.setDisable(true);
        loginButton.setText("登录中...");

        // 创建登录任务
        Task<User> loginTask = new Task<User>() {
            @Override
            protected User call() throws Exception {
                return apiService.login(username, password);
            }

            @Override
            protected void succeeded() {
                User user = getValue();
                Platform.runLater(() -> {
                    try {
                        // 打开主界面
                        Stage primaryStage = Main.getPrimaryStage();
                        primaryStage.setTitle("游戏大厅 - " + username);

                        // 将API服务传递给MainView，确保会话ID一致
                        MainView mainView = new MainView(user, apiService, appConfig);
                        primaryStage.setScene(new Scene(mainView, 800, 600));
                        primaryStage.setResizable(true);
                        primaryStage.centerOnScreen();

                        // WebSocket连接放在单独线程，避免阻塞UI
                        Main.getExecutorService().submit(() -> {
                            try {
                                // 短暂延迟确保UI已完成初始化
                                Thread.sleep(500);
                                Platform.runLater(() -> {
                                    try {
                                        mainView.connectWebSocket();
                                    } catch (Exception e) {
                                        AlertUtil.showError("连接失败", "无法连接到WebSocket服务器: " + e.getMessage());
                                    }
                                });
                            } catch (InterruptedException ignored) {
                                // 忽略中断异常
                            }
                        });
                    } catch (Exception e) {
                        AlertUtil.showError("界面加载失败", e.getMessage());
                        loginButton.setDisable(false);
                        loginButton.setText("登录/注册");
                    }
                });
            }

            @Override
            protected void failed() {
                Throwable exception = getException();
                Platform.runLater(() -> {
                    AlertUtil.showError("登录失败", exception != null ? exception.getMessage() : "未知错误");
                    loginButton.setDisable(false);
                    loginButton.setText("登录/注册");
                });
            }
        };

        // 使用ExecutorService管理任务
        Main.getExecutorService().submit(loginTask);

        // 添加超时处理逻辑
        Main.getExecutorService().submit(() -> {
            try {
                // 等待15秒后如果任务仍未完成，则取消任务
                Thread.sleep(15000);
                
                // 在JavaFX线程中安全地检查任务状态
                Platform.runLater(() -> {
                    if (loginTask.isRunning()) {
                        loginTask.cancel(true);
                        AlertUtil.showError("登录超时", "连接服务器超时，请检查网络或服务器设置");
                        loginButton.setDisable(false);
                        loginButton.setText("登录/注册");
                    }
                });
            } catch (InterruptedException ignored) {
                // 忽略中断异常
            }
        });
    }

    private void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("服务器设置");
        dialog.setHeaderText("配置服务器连接参数");

        ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField serverUrlField = new TextField(appConfig.getServerUrl());
        TextField wsUrlField = new TextField(appConfig.getWebSocketUrl());

        grid.add(new Label("服务器URL:"), 0, 0);
        grid.add(serverUrlField, 1, 0);
        grid.add(new Label("WebSocket URL:"), 0, 1);
        grid.add(wsUrlField, 1, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return ButtonType.OK;
            }
            return ButtonType.CANCEL;
        });

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                appConfig.setServerUrl(serverUrlField.getText());
                appConfig.setWebSocketUrl(wsUrlField.getText());
                appConfig.saveConfig();
            }
        });
    }
}