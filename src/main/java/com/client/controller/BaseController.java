package com.client.controller;

import com.client.config.AppProperties;
import com.client.config.StageManager;
import com.client.util.ResourceUtil;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public abstract class BaseController {

    private static final Logger logger = LoggerFactory.getLogger(BaseController.class);

    @Autowired
    protected StageManager stageManager;

    @Autowired
    protected AppProperties appProperties;

    @Autowired
    protected ResourceUtil resourceUtil;

    /**
     * 显示错误提示框
     * @param title 标题
     * @param message 消息
     */
    protected void showErrorAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("错误");
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 显示信息提示框
     * @param title 标题
     * @param message 消息
     */
    protected void showInfoAlert(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(AlertType.INFORMATION);
            alert.setTitle("提示");
            alert.setHeaderText(title);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    /**
     * 安全地在JavaFX线程上执行操作
     * @param runnable 要执行的任务
     */
    protected void runOnFXThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            runnable.run();
        } else {
            Platform.runLater(runnable);
        }
    }

    /**
     * 初始化控制器
     */
    public abstract void initialize();


    protected void executeAsync(Runnable task) {
        Thread thread = new Thread(task);
        thread.setDaemon(true);
        thread.start();
    }
}