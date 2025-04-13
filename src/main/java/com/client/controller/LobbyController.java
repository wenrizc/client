package com.client.controller;

import com.client.view.FxmlView;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;

@Controller
public class LobbyController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LobbyController.class);

    @FXML
    private TabPane tabPane;

    @FXML
    private Tab chatTab;

    @FXML
    private Tab roomsTab;

    @FXML
    private Tab profileTab;

    @FXML
    private ListView<String> userListView;

    @FXML
    private Button refreshButton;

    @FXML
    private Button logoutButton;

    @Override
    public void initialize() {
        logger.info("初始化大厅控制器");

        // 设置按钮事件
        refreshButton.setOnAction(event -> handleRefresh());
        logoutButton.setOnAction(event -> handleLogout());

        // 测试用户列表
        userListView.getItems().addAll("张三", "李四", "王五");

        // 设置标签页切换监听
        tabPane.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldTab, newTab) -> handleTabChange(newTab));
    }

    private void handleRefresh() {
        logger.debug("刷新用户列表");
        showInfoAlert("刷新", "用户列表已刷新");
    }

    private void handleLogout() {
        logger.debug("退出登录");
        stageManager.switchScene(FxmlView.LOGIN);
    }

    private void handleTabChange(Tab newTab) {
        if (newTab == roomsTab) {
            logger.debug("切换到房间列表标签页");
        } else if (newTab == chatTab) {
            logger.debug("切换到聊天标签页");
        } else if (newTab == profileTab) {
            logger.debug("切换到个人信息标签页");
        }
    }
}