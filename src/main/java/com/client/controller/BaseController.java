package com.client.controller;

import com.client.config.AppProperties;
import com.client.config.StageManager;
import com.client.util.ResourceUtil;
import jakarta.annotation.Resource;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 控制器基类
 * <p>
 * 为所有控制器提供通用功能，包括：
 * - UI线程安全操作
 * - 异步任务执行
 * - 通用对话框显示
 * - 配置访问
 * </p>
 */
public abstract class BaseController {

    private static final Logger logger = LoggerFactory.getLogger(BaseController.class);

    @Autowired
    protected StageManager stageManager;
    @Autowired
    protected AppProperties appProperties;
    @Autowired
    protected ResourceUtil resourceUtil;

    @Resource
    private ThreadPoolTaskExecutor taskExecutor;

    /**
     * 初始化控制器
     * <p>
     * 子类必须实现此方法以进行特定控制器的初始化
     * </p>
     */
    public abstract void initialize();

    /**
     * 安全地在JavaFX线程上执行操作
     * <p>
     * 如果当前已在JavaFX线程，则直接执行；否则，使用Platform.runLater执行
     * </p>
     *
     * @param runnable 要执行的任务
     */
    protected void runOnFXThread(Runnable runnable) {
        if (Platform.isFxApplicationThread()) {
            try {
                runnable.run();
            } catch (Exception e) {
                logger.error("在UI线程执行任务时出错", e);
            }
        } else {
            Platform.runLater(() -> {
                try {
                    runnable.run();
                } catch (Exception e) {
                    logger.error("在调度UI线程任务时出错", e);
                }
            });
        }
    }

    /**
     * 在后台线程异步执行任务
     * <p>
     * 使用线程池执行指定任务，避免频繁创建线程带来的开销
     * </p>
     *
     * @param task 要异步执行的任务
     */
    protected void executeAsync(Runnable task) {
        taskExecutor.execute(() -> {
            try {
                task.run();
            } catch (Exception e) {
                logger.error("执行异步任务时出错", e);
            }
        });
    }

    /**
     * 显示错误提示框
     * <p>
     * 在JavaFX线程上安全地显示错误对话框
     * </p>
     *
     * @param title 对话框标题
     * @param message 错误信息内容
     */
    protected void showErrorAlert(String title, String message) {
        runOnFXThread(() -> {
            try {
                Alert alert = new Alert(AlertType.ERROR);
                alert.setTitle("错误");
                alert.setHeaderText(title);
                alert.setContentText(message);
                alert.showAndWait();
            } catch (Exception e) {
                logger.error("显示错误对话框失败", e);
            }
        });
    }

    /**
     * 显示信息提示框
     * <p>
     * 在JavaFX线程上安全地显示信息对话框
     * </p>
     *
     * @param title 对话框标题
     * @param message 信息内容
     */
    protected void showInfoAlert(String title, String message) {
        runOnFXThread(() -> {
            try {
                Alert alert = new Alert(AlertType.INFORMATION);
                alert.setTitle("提示");
                alert.setHeaderText(title);
                alert.setContentText(message);
                alert.showAndWait();
            } catch (Exception e) {
                logger.error("显示信息对话框失败", e);
            }
        });
    }
}