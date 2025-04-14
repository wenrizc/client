package com.client.config;

import com.client.controller.BaseController;
import com.client.controller.CreateRoomController;
import com.client.controller.ServerSettingsController;
import com.client.util.ResourceUtil;
import com.client.view.FxmlView;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

@Component
public class StageManager {

    private static final Logger logger = LoggerFactory.getLogger(StageManager.class);

    private final ApplicationContext applicationContext;
    private final AppProperties appProperties;
    private Stage primaryStage;
    private final Map<String, Scene> sceneCache = new HashMap<>();
    @Autowired
    private ResourceUtil resourceUtil;


    @Autowired
    public StageManager(ApplicationContext applicationContext, AppProperties appProperties) {
        this.applicationContext = applicationContext;
        this.appProperties = appProperties;
    }

    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    public Stage getPrimaryStage() {
        return primaryStage;
    }

    public void switchScene(FxmlView view) {
        try {
            String fxmlFile = view.getFxmlFile();
            logger.debug("切换场景到: {}", fxmlFile);

            // 如果缓存中没有，则加载
            if (!sceneCache.containsKey(fxmlFile)) {
                Parent root = loadViewNodeFromFxml(view);
                Scene scene = new Scene(root);

                // 使用ResourceUtil加载CSS
                String cssFileName = "application.css";
                String cssUrl = resourceUtil.getCssUrl(cssFileName);
                if (cssUrl != null) {
                    scene.getStylesheets().add(cssUrl);
                    logger.debug("成功加载CSS: {}", cssFileName);
                } else {
                    logger.warn("无法找到CSS文件: {}", cssFileName);
                }

                sceneCache.put(fxmlFile, scene);
            }

            primaryStage.setScene(sceneCache.get(fxmlFile));
            primaryStage.sizeToScene();
            primaryStage.centerOnScreen();

            logger.info("场景已切换到: {}", view.getFxmlFile());
        } catch (Exception e) {
            logger.error("切换场景失败: " + view.getFxmlFile(), e);
            throw new RuntimeException("无法加载场景", e);
        }
    }

    public void showDialog(FxmlView view, String title) {
        try {
            Parent root = loadViewNodeFromFxml(view);
            Scene scene = new Scene(root);

            // 使用ResourceUtil加载CSS
            String cssFileName = "application.css";
            String cssUrl = resourceUtil.getCssUrl(cssFileName);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl);
                logger.debug("成功加载CSS: {}", cssFileName);
            } else {
                logger.warn("无法找到CSS文件: {}", cssFileName);
            }

            Stage dialogStage = new Stage();
            dialogStage.setTitle(title);
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initOwner(primaryStage);
            dialogStage.setScene(scene);
            dialogStage.showAndWait();

            logger.info("对话框已显示: {}", view.getFxmlFile());
        } catch (Exception e) {
            logger.error("显示对话框失败: " + view.getFxmlFile(), e);
            throw new RuntimeException("无法加载对话框", e);
        }
    }

    private Parent loadViewNodeFromFxml(FxmlView view) throws IOException {
        try {
            URL fxmlUrl = resourceUtil.getFxmlResource(view.getFxmlFile());
            if (fxmlUrl == null) {
                throw new IOException("无法找到FXML文件: " + view.getFxmlFile());
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            loader.setControllerFactory(applicationContext::getBean);
            return loader.load();
        } catch (IOException e) {
            logger.error("加载FXML失败: " + view.getFxmlFile(), e);
            throw e;
        }
    }

    public void clearSceneCache() {
        sceneCache.clear();
    }

    public <T extends BaseController> T openDialog(FxmlView view) {
        try {
            URL fxmlUrl = resourceUtil.getFxmlResource(view.getFxmlFile());
            if (fxmlUrl == null) {
                logger.error("无法找到FXML资源: {}", view.getFxmlFile());
                throw new IOException("无法找到FXML文件: " + view.getFxmlFile());
            }

            FXMLLoader loader = new FXMLLoader(fxmlUrl);
            loader.setControllerFactory(applicationContext::getBean);

            Parent root = loader.load();
            Scene scene = new Scene(root);

            // 加载CSS
            String cssFileName = "application.css";
            String cssUrl = resourceUtil.getCssUrl(cssFileName);
            if (cssUrl != null) {
                scene.getStylesheets().add(cssUrl);
                logger.debug("成功加载CSS: {}", cssFileName);
            }

            Stage dialogStage = new Stage();
            dialogStage.setTitle(view.getTitle());
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initOwner(primaryStage);
            dialogStage.setScene(scene);

            // 获取控制器实例
            T controller = loader.getController();

            // 如果控制器支持设置对话框Stage
            if (controller instanceof ServerSettingsController) {
                ((ServerSettingsController) controller).setDialogStage(dialogStage);
            } else if (controller instanceof CreateRoomController) {
                ((CreateRoomController) controller).setDialogStage(dialogStage);
            }

            dialogStage.showAndWait();

            return controller;

        } catch (Exception e) {
            logger.error("显示对话框失败: " + view.getFxmlFile(), e);
            throw new RuntimeException("无法加载对话框", e);
        }
    }

}