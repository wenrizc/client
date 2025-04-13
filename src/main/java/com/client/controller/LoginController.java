package com.client.controller;

import com.client.view.FxmlView;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

@Controller
public class LoginController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LoginController.class);

    @FXML
    private TextField usernameField;

    @FXML
    private PasswordField passwordField;

    @FXML
    private Button loginButton;

    @FXML
    private Button settingsButton;

    @Override
    public void initialize() {
        logger.info("初始化登录控制器");

        // 设置按钮事件
        loginButton.setOnAction(event -> handleLogin());
        settingsButton.setOnAction(event -> handleSettings());
    }

    private void handleLogin() {
        logger.debug("处理登录事件");

        String username = usernameField.getText().trim();
        String password = passwordField.getText().trim();

        if (username.isEmpty()) {
            showErrorAlert("登录失败", "用户名不能为空");
            return;
        }

        if (password.isEmpty()) {
            showErrorAlert("登录失败", "密码不能为空");
            return;
        }

        // 这里暂时只是切换到大厅界面
        logger.info("登录成功，切换到大厅界面");
        stageManager.switchScene(FxmlView.LOBBY);
    }

    private void handleSettings() {
        logger.debug("打开设置对话框");
        showInfoAlert("服务器设置", "服务器设置功能将在后续实现");
    }
}