package com.client;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.client.config.AppConfig;
import com.client.ui.LoginView;
import com.client.ui.MainView;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class Main extends Application {

    private static Stage primaryStage;
    private AppConfig appConfig;
    // 添加线程池管理线程
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    @Override
    public void start(Stage stage) {
        primaryStage = stage;
        appConfig = new AppConfig();

        // 初始化登录界面
        primaryStage.setTitle("游戏大厅 - 登录");
        primaryStage.setScene(new Scene(new LoginView(), 400, 300));
        primaryStage.setResizable(false);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }

    public static Stage getPrimaryStage() {
        return primaryStage;
    }

    // 添加获取线程池的静态方法
    public static ExecutorService getExecutorService() {
        return executorService;
    }

    @Override
    public void stop() {
        // 应用关闭时执行清理操作
        System.out.println("应用关闭中...");

        // 关闭线程池
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(800, TimeUnit.MILLISECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }

        // 尝试获取并关闭WebSocket连接
        Scene scene = primaryStage.getScene();
        if (scene != null && scene.getRoot() instanceof MainView) {
            MainView mainView = (MainView) scene.getRoot();
            try {
                // 发送退出登录请求
                mainView.logoutAndDisconnect();
            } catch (Exception e) {
                System.err.println("关闭WebSocket连接失败: " + e.getMessage());
            }
        }
    }
}