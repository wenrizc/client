package com.client.ui;

import com.client.config.AppConfig;
import com.client.model.Message;
import com.client.model.Room;
import com.client.model.User;
import com.client.service.ApiService;
import com.client.service.WebSocketService;
import com.client.util.AlertUtil;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class RoomView extends BorderPane {
    private final Room room;
    private final User currentUser;
    private final ApiService apiService;
    private final WebSocketService webSocketService;
    private final AppConfig appConfig;
    private final Stage stage;

    private Label statusLabel;
    private ListView<String> playersListView;

    public RoomView(Room room, User currentUser, ApiService apiService, WebSocketService webSocketService, AppConfig appConfig, Stage stage) {
        this.room = room;
        this.currentUser = currentUser;
        this.apiService = apiService;
        this.webSocketService = webSocketService;
        this.appConfig = appConfig;
        this.stage = stage;

        setPadding(new Insets(10));

        // 创建顶部标题栏
        setTop(createHeader());

        // 创建左侧玩家列表
        setLeft(createPlayersList());

        // 创建中间内容区域
        setCenter(createChatArea());

        // 创建底部控制栏
        setBottom(createControlBar());

        // 设置房间信息更新处理器
        setupHandlers();
    }

    private VBox createHeader() {
        VBox header = new VBox(5);
        header.setPadding(new Insets(10));
        header.setAlignment(Pos.CENTER);

        Label titleLabel = new Label("房间: " + room.getName());
        titleLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold;");

        Label infoLabel = new Label("游戏类型: " + room.getGameType() + " | 状态: " + room.getStatus());
        infoLabel.setStyle("-fx-font-style: italic;");

        header.getChildren().addAll(titleLabel, infoLabel);
        return header;
    }

    private VBox createPlayersList() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setPrefWidth(150);

        Label titleLabel = new Label("玩家列表");
        titleLabel.setStyle("-fx-font-weight: bold;");

        playersListView = new ListView<>();
        updatePlayersList();
        VBox.setVgrow(playersListView, Priority.ALWAYS);

        vbox.getChildren().addAll(titleLabel, playersListView);
        return vbox;
    }

    private VBox createChatArea() {
        VBox chatBox = new VBox(10);
        chatBox.setPadding(new Insets(10));

        // 聊天记录显示区域
        ListView<Message> chatListView = new ListView<>();
        chatListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Message message, boolean empty) {
                super.updateItem(message, empty);
                if (empty || message == null) {
                    setText(null);
                    setStyle("");
                } else {
                    setText(String.format("[%s] %s: %s",
                            message.getFormattedTime(), message.getSender(), message.getContent()));
                    if (message.getSender().equals("系统")) {
                        setStyle("-fx-text-fill: blue;");
                    } else if (message.getSender().equals(currentUser.getUsername())) {
                        setStyle("-fx-text-fill: green;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });
        VBox.setVgrow(chatListView, Priority.ALWAYS);

        // 消息输入区域
        HBox inputBox = new HBox(10);
        TextField messageField = new TextField();
        messageField.setPromptText("输入消息...");
        messageField.setPrefWidth(600);
        HBox.setHgrow(messageField, Priority.ALWAYS);

        Button sendButton = new Button("发送");
        sendButton.setDefaultButton(true);

        inputBox.getChildren().addAll(messageField, sendButton);

        new Thread(() -> {
            try {
                List<Map<String, Object>> messageHistory = apiService.getRoomMessages(room.getId());
                List<Message> messages = new ArrayList<>();

                for (Map<String, Object> messageData : messageHistory) {
                    Message message = new Message(
                            (String) messageData.get("sender"),
                            (String) messageData.get("message"),
                            ((Number) messageData.get("timestamp")).longValue(),
                            "ROOM_MESSAGE"
                    );
                    messages.add(message);
                }

                Platform.runLater(() -> {
                    chatListView.getItems().clear();
                    chatListView.getItems().addAll(messages);
                    if (!messages.isEmpty()) {
                        chatListView.scrollTo(messages.size() - 1);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        AlertUtil.showError("错误", "无法加载房间聊天记录: " + e.getMessage())
                );
            }
        }).start();

        webSocketService.subscribeToRoomMessages(room.getId());
        webSocketService.setRoomMessageHandler(message -> {
            chatListView.getItems().add(message);
            chatListView.scrollTo(chatListView.getItems().size() - 1);
        });

        // 发送消息事件处理
        sendButton.setOnAction(e -> {
            String message = messageField.getText().trim();
            if (!message.isEmpty()) {
                messageField.clear();
                webSocketService.sendRoomMessage(room.getId(), message);
            }
        });

        chatBox.getChildren().addAll(chatListView, inputBox);
        return chatBox;
    }

    private HBox createControlBar() {
        HBox controlBar = new HBox(10);
        controlBar.setAlignment(Pos.CENTER);
        controlBar.setPadding(new Insets(10));

        // 游戏控制按钮
        Button startGameButton = new Button("开始游戏");
        startGameButton.setOnAction(e -> {
            webSocketService.startGame(room.getId());
        });
        startGameButton.setDisable(!room.isCreator(currentUser.getUsername()) || "PLAYING".equals(room.getStatus()));

        Button leaveRoomButton = new Button("离开房间");

        // N2N连接信息按钮
        Button n2nInfoButton = new Button("N2N连接信息");
        n2nInfoButton.setOnAction(e -> showN2NInfoDialog());

        // 状态显示
        statusLabel = new Label("房间状态: " + room.getStatus());
        HBox.setHgrow(statusLabel, Priority.ALWAYS);
        statusLabel.setAlignment(Pos.CENTER_RIGHT);

        controlBar.getChildren().addAll(startGameButton, leaveRoomButton, n2nInfoButton, statusLabel);

        // 按钮事件处理
        leaveRoomButton.setOnAction(e -> leaveRoom());

        startGameButton.setOnAction(e -> {
            // TODO: 通过WebSocket发送开始游戏请求
            AlertUtil.showInfo("功能开发中", "开始游戏功能正在开发中");
        });

        return controlBar;
    }

    private void setupHandlers() {
        // 设置房间更新处理器
        webSocketService.setRoomUpdateHandler(updatedRoom -> {
            // 只处理当前房间的更新
            if (updatedRoom.getId().equals(room.getId())) {
                // 更新本地房间数据
                room.setStatus(updatedRoom.getStatus());
                room.setPlayers(updatedRoom.getPlayers());

                // 更新UI
                updatePlayersList();

                // 根据房间状态更新UI
                boolean isPlaying = "PLAYING".equals(updatedRoom.getStatus());
                boolean isCreator = room.isCreator(currentUser.getUsername());

                // 更新开始游戏按钮状态
                Platform.runLater(() -> {
                    // 假设startGameButton是类的成员变量，如果不是，需要调整此部分代码
                    // 找到底部控制栏中的开始游戏按钮
                    Node bottomBar = getBottom();
                    if (bottomBar instanceof HBox) {
                        ((HBox) bottomBar).getChildren().stream()
                                .filter(node -> node instanceof Button && ((Button) node).getText().equals("开始游戏"))
                                .findFirst()
                                .ifPresent(button ->
                                        ((Button) button).setDisable(!isCreator || isPlaying)
                                );
                    }

                    // 更新状态标签
                    statusLabel.setText("房间状态: " + updatedRoom.getStatus());
                });
            }
        });

        // 设置房间详情处理器
        webSocketService.setRoomDetailHandler(roomDetail -> {
            // 处理更详细的房间信息更新，例如N2N网络信息
            String status = (String) roomDetail.get("status");
            Platform.runLater(() -> {
                statusLabel.setText("房间状态: " + status);
            });
        });
    }

    private void updatePlayersList() {
        playersListView.getItems().clear();
        for (String player : room.getPlayers()) {
            if (player.equals(room.getCreatorUsername())) {
                playersListView.getItems().add(player + " (房主)");
            } else {
                playersListView.getItems().add(player);
            }
        }
    }

    private void leaveRoom() {
        if (AlertUtil.showConfirmation("离开房间", "确定要离开当前房间吗？")) {
            try {
                webSocketService.leaveRoom();
                // 返回大厅
                returnToLobby();
            } catch (Exception e) {
                AlertUtil.showError("错误", "离开房间失败: " + e.getMessage());
            }
        }
    }

    private void returnToLobby() {
        stage.setTitle("游戏大厅 - " + currentUser.getUsername());
        stage.getScene().setRoot(new MainView(currentUser, apiService, appConfig));
    }

    private void showN2NInfoDialog() {
        // TODO: 实现显示N2N连接信息
        String networkName = room.getN2nNetworkName() != null ? room.getN2nNetworkName() : "未配置";
        String networkSecret = room.getN2nNetworkSecret() != null ? room.getN2nNetworkSecret() : "未配置";

        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("N2N连接信息");
        alert.setHeaderText("本功能仅做展示，实际连接功能待实现");

        TextArea textArea = new TextArea(
                "网络名称: " + networkName + "\n" +
                        "网络密钥: " + networkSecret + "\n\n" +
                        "连接指令: \n" +
                        "edge -c " + networkName + " -k " + networkSecret + " -l supernode:1234 -a 10.0.0.x"
        );
        textArea.setEditable(false);
        textArea.setWrapText(true);

        alert.getDialogPane().setContent(textArea);
        alert.showAndWait();
    }
}