package com.client.controller;

import com.client.model.Room;
import com.client.service.api.RoomApiService;
import com.client.view.FxmlView;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

@Controller
public class CreateRoomController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(CreateRoomController.class);

    @FXML private JFXTextField roomNameField;
    @FXML private JFXTextField gameNameField;
    @FXML private Spinner<Integer> maxPlayersSpinner;
    @FXML private JFXButton createButton;
    @FXML private JFXButton cancelButton;
    @FXML private Label errorLabel;

    @Autowired private RoomApiService roomApiService;

    private Stage dialogStage;

    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    @Override
    public void initialize() {
        logger.info("初始化创建房间控制器");

        // 初始化玩家数量微调器
        SpinnerValueFactory<Integer> valueFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 10, 4);
        maxPlayersSpinner.setValueFactory(valueFactory);

        // 设置按钮事件
        createButton.setOnAction(e -> createRoom());
        cancelButton.setOnAction(e -> closeDialog());

        // 添加输入监听，清除错误
        roomNameField.textProperty().addListener((obs, old, newVal) -> hideError());
        gameNameField.textProperty().addListener((obs, old, newVal) -> hideError()); // 更新监听器
    }

    /**
     * 创建房间
     */
    private void createRoom() {
        String roomName = roomNameField.getText().trim();
        String gameName = gameNameField.getText().trim();
        int maxPlayers = maxPlayersSpinner.getValue();

        // 验证输入
        if (roomName.isEmpty()) {
            showError("请输入房间名称");
            return;
        }

        if (gameName.isEmpty()) {
            showError("请输入游戏名称");
            return;
        }

        createButton.setDisable(true);
        createButton.setText("创建中...");

        // 异步执行创建操作
        executeAsync(() -> {
            try {
                Room room = roomApiService.createRoom(roomName, gameName, maxPlayers);

                // 创建成功，关闭对话框并跳转到房间界面
                runOnFXThread(() -> {
                    dialogStage.close();
                    stageManager.switchScene(FxmlView.ROOM);
                });
            } catch (Exception e) {
                logger.error("创建房间失败", e);
                runOnFXThread(() -> {
                    showError("创建房间失败: " + e.getMessage());
                    createButton.setDisable(false);
                    createButton.setText("创建");
                });
            }
        });
    }

    /**
     * 显示错误信息
     */
    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    /**
     * 隐藏错误信息
     */
    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }

    /**
     * 关闭对话框
     */
    private void closeDialog() {
        dialogStage.close();
    }
}