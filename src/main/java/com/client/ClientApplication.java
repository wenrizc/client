package com.client;

import com.client.config.AppProperties;
import com.client.config.StageManager;
import com.client.view.FxmlView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Lazy;

@Lazy
@SpringBootApplication
public class ClientApplication extends Application {

    private static final Logger logger = LoggerFactory.getLogger(ClientApplication.class);
    private ConfigurableApplicationContext applicationContext;

    @Override
    public void init() {
        try {
            logger.info("正在初始化Spring Boot应用程序上下文...");
            applicationContext = SpringApplication.run(ClientApplication.class);
            logger.info("Spring Boot应用程序上下文初始化完成");
        } catch (Exception e) {
            logger.error("Spring Boot应用程序上下文初始化失败", e);
            throw e;
        }
    }

    @Override
    public void start(Stage primaryStage) {
        try {
            logger.info("启动JavaFX应用程序...");
            StageManager stageManager = applicationContext.getBean(StageManager.class);
            AppProperties appProperties = applicationContext.getBean(AppProperties.class);

            primaryStage.setTitle(appProperties.getStageTitle());
            primaryStage.setWidth(appProperties.getStageWidth());
            primaryStage.setHeight(appProperties.getStageHeight());
            primaryStage.setResizable(appProperties.isStageResizable());

            stageManager.setPrimaryStage(primaryStage);
            stageManager.switchScene(FxmlView.LOGIN);

            primaryStage.centerOnScreen();
            primaryStage.show();
            logger.info("JavaFX应用程序启动完成");
        } catch (Exception e) {
            logger.error("JavaFX应用程序启动失败", e);
            throw e;
        }
    }

    @Override
    public void stop() {
        try {
            logger.info("正在关闭Spring Boot应用程序上下文...");
            applicationContext.close();
            Platform.exit();
            logger.info("应用程序已关闭");
        } catch (Exception e) {
            logger.error("关闭Spring Boot应用程序上下文失败", e);
            throw e;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}