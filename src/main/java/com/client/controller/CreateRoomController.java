package com.client.controller;

import com.client.model.Room;
import com.client.service.RoomApiService;
import com.client.view.FxmlView;
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

/**
 * 创建房间对话框的控制器
 * <p>
 * 负责处理创建新游戏房间的用户界面交互和业务逻辑。
 * 提供表单验证、错误提示和服务器交互功能。
 * </p>
 */
@Controller
public class CreateRoomController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(CreateRoomController.class);

    @FXML private JFXTextField roomNameField;
    @FXML private JFXTextField gameNameField;
    @FXML private Spinner<Integer> maxPlayersSpinner;
    @FXML private JFXButton createButton;
    @FXML private JFXButton cancelButton;
    @FXML private Label errorLabel;

    @Autowired
    private RoomApiService roomApiService;

    private Stage dialogStage;

    /**
     * 初始化控制器
     * <p>
     * 设置UI控件初始状态、事件监听器和默认值
     * </p>
     */
    @Override
    public void initialize() {
        logger.info("初始化创建房间控制器");
        initializeSpinner();
        setupEventHandlers();
        setupInputListeners();
        hideError();
    }

    /**
     * 设置对话框Stage
     * <p>
     * 由外部调用，提供对话框窗口的引用以便控制其显示和关闭
     * </p>
     *
     * @param dialogStage 对话框的JavaFX Stage对象
     */
    public void setDialogStage(Stage dialogStage) {
        this.dialogStage = dialogStage;
    }

    /**
     * 初始化玩家数量选择器
     */
    private void initializeSpinner() {
        SpinnerValueFactory<Integer> valueFactory =
                new SpinnerValueFactory.IntegerSpinnerValueFactory(2, 10, 4);
        maxPlayersSpinner.setValueFactory(valueFactory);
    }

    /**
     * 设置事件处理器
     */
    private void setupEventHandlers() {
        createButton.setOnAction(e -> createRoom());
        cancelButton.setOnAction(e -> closeDialog());
    }

    /**
     * 设置输入监听器
     */
    private void setupInputListeners() {
        roomNameField.textProperty().addListener((obs, old, newVal) -> hideError());
        gameNameField.textProperty().addListener((obs, old, newVal) -> hideError());
    }

    /**
     * 创建房间
     * <p>
     * 验证用户输入，调用API创建房间，并处理成功或失败的结果
     * </p>
     */
    private void createRoom() {
        String roomName = roomNameField.getText().trim();
        String gameName = gameNameField.getText().trim();
        int maxPlayers = maxPlayersSpinner.getValue();
        if (!validateInputs(roomName, gameName)) {
            return;
        }
        runOnFXThread(() -> {
            createButton.setDisable(true);
            createButton.setText("创建中...");
        });

        // 异步执行创建房间操作
        executeAsync(() -> {
            try {
                Room room = roomApiService.createRoom(roomName, gameName, maxPlayers);
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
     * 验证用户输入
     *
     * @param roomName 房间名称
     * @param gameName 游戏名称
     * @return 验证是否通过
     */
    private boolean validateInputs(String roomName, String gameName) {
        if (roomName.isEmpty()) {
            showError("请输入房间名称");
            return false;
        }

        if (gameName.isEmpty()) {
            showError("请输入游戏名称");
            return false;
        }

        return true;
    }

    /**
     * 关闭对话框
     * <p>
     * 取消创建房间并关闭对话框窗口
     * </p>
     */
    private void closeDialog() {
        dialogStage.close();
    }

    /**
     * 显示错误信息
     * <p>
     * 在界面上显示验证错误或服务器返回的错误信息
     * </p>
     *
     * @param message 要显示的错误消息
     */
    private void showError(String message) {
        runOnFXThread(() -> {
            errorLabel.setText(message);
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        });
    }

    /**
     * 隐藏错误信息
     * <p>
     * 清除界面上显示的错误消息
     * </p>
     */
    private void hideError() {
        runOnFXThread(() -> {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        });
    }
}