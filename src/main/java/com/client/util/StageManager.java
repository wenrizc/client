package com.client.util;

import com.client.controller.BaseController;
import com.client.controller.CreateRoomController;
import com.client.controller.ServerSettingsController;
import com.client.service.NetworkStatusService;
import com.client.service.WebSocketService;
import com.client.util.n2n.N2NClientManager;
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

@Component
public class StageManager {

    private static final Logger logger = LoggerFactory.getLogger(StageManager.class);

    private final ApplicationContext applicationContext;
    private final ResourceUtil resourceUtil;
    private Stage primaryStage;

    private final WebSocketService webSocketService;
    private final NetworkStatusService networkStatusService;
    private final N2NClientManager n2nClientManager;

    @Autowired
    public StageManager(ApplicationContext applicationContext, ResourceUtil resourceUtil,
                        WebSocketService webSocketService,
                        NetworkStatusService networkStatusService,
                        N2NClientManager n2nClientManager) {
        this.applicationContext = applicationContext;
        this.resourceUtil = resourceUtil;
        this.webSocketService = webSocketService;
        this.networkStatusService = networkStatusService;
        this.n2nClientManager = n2nClientManager;
    }

    /**
     * 设置主舞台引用
     *
     * @param primaryStage 应用程序主舞台
     */
    public void setPrimaryStage(Stage primaryStage) {
        this.primaryStage = primaryStage;
    }

    /**
     * 获取主舞台引用
     *
     * @return 应用程序主舞台
     */
    public Stage getPrimaryStage() {
        return primaryStage;
    }

    /**
     * 切换到指定视图
     * <p>
     * 将主舞台的场景切换为指定的FXML视图。
     * 每次切换都会重新加载场景，确保使用最新状态。
     * </p>
     *
     * @param view 要切换到的视图
     */
    public void switchScene(FxmlView view) {
        try {
            String fxmlFile = view.getFxmlFile();
            logger.debug("切换场景到: {}", fxmlFile);

            if (view == FxmlView.LOGIN) {
                pauseScheduledTasks();
            }

            // 移除缓存检查，总是重新加载场景
            Parent root = loadViewNodeFromFxml(view);
            Scene scene = new Scene(root);
            applyCssToScene(scene);

            primaryStage.setScene(scene);
            primaryStage.sizeToScene();
            primaryStage.centerOnScreen();

            logger.info("场景已切换到: {}", fxmlFile);
        } catch (Exception e) {
            logger.error("切换场景失败: {}", view.getFxmlFile(), e);
            throw new RuntimeException("无法加载场景", e);
        }
    }

    /**
     * 暂停所有定时任务
     * 在用户退出登录返回登录界面时调用
     */
    public void pauseScheduledTasks() {
        logger.info("退出登录：暂停所有定时任务");

        // 停止WebSocket相关定时任务
        if (webSocketService != null) {
            webSocketService.stopHeartbeat();
            webSocketService.stopHealthCheck();
        }

        // 停止网络状态监控
        if (networkStatusService != null) {
            networkStatusService.stopMonitoring();
        }

        // 停止N2N客户端监控
        if (n2nClientManager != null && n2nClientManager.isRunning()) {
            n2nClientManager.stopN2NClient();
        }
    }


    /**
     * 打开对话框并返回控制器
     * <p>
     * 创建一个模态对话框，并返回关联的控制器实例。
     * 对特定类型的控制器提供额外处理。
     * </p>
     *
     * @param <T> 控制器类型
     * @param view 要显示的视图
     * @return 对话框控制器实例
     */
    public <T extends BaseController> T openDialog(FxmlView view) {
        try {
            FXMLLoader loader = createFxmlLoader(view);
            Parent root = loader.load();
            T controller = loader.getController();

            Stage dialogStage = new Stage();
            dialogStage.initModality(Modality.APPLICATION_MODAL);
            dialogStage.initOwner(primaryStage);
            dialogStage.setResizable(false);

            Scene scene = new Scene(root);
            applyCssToScene(scene);
            dialogStage.setScene(scene);

            // 针对特定控制器设置舞台引用
            if (controller instanceof ServerSettingsController) {
                ((ServerSettingsController) controller).setDialogStage(dialogStage);
            } else if (controller instanceof CreateRoomController) {
                ((CreateRoomController) controller).setDialogStage(dialogStage);
            }

            dialogStage.showAndWait();
            return controller;
        } catch (IOException e) {
            logger.error("打开对话框失败: {}", view.getFxmlFile(), e);
            throw new RuntimeException("无法加载对话框", e);
        }
    }

    /**
     * 清除场景缓存
     * 保留此方法以便向后兼容，但不再有实际作用
     */
    public void clearSceneCache() {
        // 由于不再使用缓存，此方法将不再有实际作用
        logger.debug("场景缓存清除方法已调用（已无实际作用）");
    }

    /**
     * 从FXML加载视图节点
     *
     * @param view 要加载的视图
     * @return 加载的父节点
     * @throws IOException 如果加载失败
     */
    private Parent loadViewNodeFromFxml(FxmlView view) throws IOException {
        FXMLLoader loader = createFxmlLoader(view);
        return loader.load();
    }

    /**
     * 创建FXML加载器
     *
     * @param view 要加载的视图
     * @return 配置好的FXML加载器
     * @throws IOException 如果找不到FXML资源
     */
    private FXMLLoader createFxmlLoader(FxmlView view) throws IOException {
        URL fxmlUrl = resourceUtil.getFxmlResource(view.getFxmlFile());
        if (fxmlUrl == null) {
            logger.error("无法找到FXML资源: {}", view.getFxmlFile());
            throw new IOException("无法找到FXML文件: " + view.getFxmlFile());
        }

        FXMLLoader loader = new FXMLLoader(fxmlUrl);
        loader.setControllerFactory(applicationContext::getBean);
        return loader;
    }

    /**
     * 应用CSS到场景
     *
     * @param scene 要应用CSS的场景
     */
    private void applyCssToScene(Scene scene) {
        String cssFileName = "application.css";
        String cssUrl = resourceUtil.getCssUrl(cssFileName);
        if (cssUrl != null) {
            scene.getStylesheets().add(cssUrl);
            logger.debug("成功加载CSS: {}", cssFileName);
        } else {
            logger.warn("无法找到CSS文件: {}", cssFileName);
        }
    }
}