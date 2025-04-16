package com.client.controller;

import com.client.model.Message;
import com.client.model.Room;
import com.client.model.User;
import com.client.service.WebSocketService;
import com.client.service.api.MessageApiService;
import com.client.service.api.RoomApiService;
import com.client.service.api.UserApiService;
import com.client.session.SessionManager;
import com.client.util.AlertHelper;
import com.client.view.FxmlView;
import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Controller
public class RoomController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(RoomController.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    // FXML 注入
    @FXML
    private Label roomTitleLabel;
    @FXML
    private Label userNameLabel;
    @FXML
    private Label roomNameLabel;
    @FXML
    private Label gameNameLabel;
    @FXML
    private Label statusLabel;
    @FXML
    private ListView<String> playerListView;
    @FXML
    private ScrollPane chatScrollPane;
    @FXML
    private VBox chatMessagesBox;
    @FXML
    private TextField messageField;
    @FXML
    private JFXButton sendButton;
    @FXML
    private JFXButton startGameButton;
    @FXML
    private JFXButton leaveRoomButton;
    @FXML
    private JFXButton networkInfoButton;
    @FXML
    private JFXButton helpButton;
    @FXML
    private Label roomStatusLabel;

    // 注入服务
    @Autowired
    private RoomApiService roomApiService;
    @Autowired
    private UserApiService userApiService;
    @Autowired
    private MessageApiService messageApiService;
    @Autowired
    private WebSocketService webSocketService;
    @Autowired
    private SessionManager sessionManager;

    // 数据
    private Room currentRoom;
    private ObservableList<String> playersList = FXCollections.observableArrayList();
    private Timer refreshTimer;

    @Override
    public void initialize() {
        logger.info("初始化房间控制器");

        // 设置玩家列表
        playerListView.setItems(playersList);
        playerListView.setCellFactory(this::createPlayerListCell);

        // 设置按钮事件
        sendButton.setOnAction(event -> sendMessage());
        messageField.setOnAction(event -> sendMessage());
        startGameButton.setOnAction(event -> startGame());
        leaveRoomButton.setOnAction(event -> leaveRoom());
        networkInfoButton.setOnAction(event -> showNetworkInfo());
        helpButton.setOnAction(event -> showHelpDialog());

        // 自动滚动聊天窗口
        chatMessagesBox.heightProperty().addListener((observable, oldValue, newValue) -> {
            chatScrollPane.setVvalue(1.0);
        });

        // 加载房间数据
        loadRoomData();

        // 设置定时刷新
        setupPeriodicRefresh();
    }

    private void loadRoomData() {
        executeAsync(() -> {
            try {
                // 获取当前房间信息
                Room room = roomApiService.getCurrentUserRoom();
                if (room != null) {
                    runOnFXThread(() -> {
                        updateRoomInfo(room);
                        // 在成功获取房间信息后再初始化WebSocket连接
                        initWebSocketConnection();
                    });
                    // 加载房间历史消息ewq
                    loadRoomMessages(room.getId());
                } else {
                    // 如果找不到房间，返回大厅
                    runOnFXThread(() -> {
                        AlertHelper.showError("错误", "房间错误", "无法加载房间信息，将返回大厅");
                        returnToLobby();
                    });
                }
            } catch (Exception e) {
                logger.error("加载房间信息出错", e);
                runOnFXThread(() -> {
                    AlertHelper.showError("错误", "加载失败", e.getMessage());
                    returnToLobby();
                });
            }
        });
    }

    private void updateRoomInfo(Room room) {
        if (room == null)
            return;

        currentRoom = room;

        // 更新标题和信息
        String roomName = room.getName();
        roomTitleLabel.setText("房间: " + roomName + " - " + sessionManager.getCurrentUser().getUsername());
        roomNameLabel.setText("房间: " + roomName);
        gameNameLabel.setText("游戏名: " + room.getGameName());

        // 更新状态
        String status = formatRoomStatus(room.getStatus());
        statusLabel.setText("状态: " + status);
        roomStatusLabel.setText("房间状态: " + status);

        // 更新玩家列表
        updatePlayersList(room);

        // 更新按钮状态
        updateButtonStates(room);
    }

    private void updatePlayersList(Room room) {
        playersList.clear();

        if (room != null && room.getPlayers() != null) {
            // 确保房主显示在第一位
            String creator = room.getCreatorUsername();
            if (creator != null) {
                playersList.add(creator + " (房主)");

                // 添加其他玩家
                room.getPlayers().stream()
                        .filter(player -> !player.equals(creator))
                        .forEach(player -> playersList.add(player));
            } else {
                playersList.addAll(room.getPlayers());
            }
        }
    }

    private void updateButtonStates(Room room) {
        if (room == null)
            return;

        // 获取当前用户名
        String currentUsername = sessionManager.getCurrentUser().getUsername();

        // 只有房主且房间处于等待状态才能开始游戏
        boolean isCreator = currentUsername != null && currentUsername.equals(room.getCreatorUsername());

        // 更灵活地判断房间状态 - 兼容不同格式的状态值
        String status = room.getStatus();
        boolean isWaiting = status != null &&
                (status.equals("WAITING") || status.equalsIgnoreCase("waiting"));
        boolean isPlaying = status != null &&
                (status.equals("PLAYING") || status.equalsIgnoreCase("playing"));

        boolean hasEnoughPlayers = room.getPlayers() != null && room.getPlayers().size() >= 2;

        // 更新按钮状态
        if (isPlaying && isCreator) {
            // 游戏进行中时，房主可以结束游戏
            startGameButton.setText("结束游戏");
            startGameButton.setDisable(false);
        } else {
            // 非游戏进行中时，显示开始游戏
            startGameButton.setText("开始游戏");

            // 禁用按钮同时添加提示文本
            boolean shouldDisable = !isCreator || !isWaiting || !hasEnoughPlayers;
            startGameButton.setDisable(shouldDisable);

            // 添加提示信息
            if (isCreator && isWaiting && !hasEnoughPlayers) {
                // 如果是房主、房间等待中，但玩家数量不足，添加提示
                startGameButton.setTooltip(new Tooltip("需要至少2名玩家才能开始游戏"));
            } else {
                startGameButton.setTooltip(null);
            }
        }

        // 更新房间状态标签
        String statusText = "房间状态: " + (isWaiting ? "等待中" : isPlaying ? "游戏中" : "未知");
        roomStatusLabel.setText(statusText);
    }

    private void setupPeriodicRefresh() {
        // 创建定时刷新任务
        refreshTimer = new Timer(true);
        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                refreshRoomInfo();
            }
        }, 5000, 5000); // 每5秒刷新一次房间状态
    }

    private void refreshRoomInfo() {
        executeAsync(() -> {
            try {
                Room room = roomApiService.getCurrentUserRoom();
                if (room != null) {
                    runOnFXThread(() -> updateRoomInfo(room));
                } else {
                    // 如果房间不存在了（可能被删除），返回大厅
                    runOnFXThread(this::returnToLobby);
                }
            } catch (Exception e) {
                logger.error("刷新房间信息出错", e);
            }
        });
    }

    private void initWebSocketConnection() {
        // 如果已连接，直接订阅
        if (webSocketService.isConnected()) {
            subscribeToTopics();
        } else {
            // 尝试连接，如果连接成功，立即订阅
            boolean connected = webSocketService.connect();
            if (connected) {
                subscribeToTopics();
            } else {
                logger.warn("WebSocket连接失败，无法订阅房间消息");
                // 可以添加重试逻辑或显示错误消息
            }
        }
    }

    private void subscribeToTopics() {
        if (currentRoom == null) {
            logger.warn("无法订阅房间主题: 当前房间为空");
            return;
        }

        Long roomId = currentRoom.getId();

        // 取消可能存在的系统消息订阅
        webSocketService.unsubscribeFromSystemNotifications();

        // 使用封装的subscribeRoomMessages方法订阅房间消息
        logger.debug("开始订阅房间 {} 的聊天消息", roomId);
        webSocketService.subscribeRoomMessages(roomId, this::handleRoomMessage);
        logger.info("已成功订阅房间 {} 的聊天消息", roomId);

        // 订阅房间状态更新
        logger.debug("开始订阅房间更新主题");
        webSocketService.subscribe("/topic/rooms.updates", Map.class, roomUpdate -> {
            Long updateRoomId = ((Number) roomUpdate.get("roomId")).longValue();
            // 只处理当前房间的更新
            if (roomId.equals(updateRoomId)) {
                handleRoomUpdate(roomUpdate);
            }
        });
        logger.info("已成功订阅房间更新主题");

        // 订阅个人通知
        logger.debug("开始订阅个人通知消息");
        webSocketService.subscribe("/user/queue/notifications", Map.class, this::handleSystemNotification);
        logger.info("已成功订阅个人通知消息");

        logger.info("房间 {} 的所有消息主题订阅完成", roomId);
    }

    private void handleRoomUpdate(Map<String, Object> roomData) {
        if (currentRoom == null)
            return;

        // 获取房间ID和动作
        Number roomId = (Number) roomData.get("roomId");
        String action = (String) roomData.get("action");
        String username = (String) roomData.get("username");

        // 只处理当前房间的更新
        if (roomId != null && roomId.longValue() == currentRoom.getId()) {
            Platform.runLater(() -> {
                // 根据动作类型更新UI
                if ("JOINED".equals(action)) {
                    displaySystemMessage(username + " 加入了房间");
                    refreshRoomInfo();
                } else if ("LEFT".equals(action)) {
                    displaySystemMessage(username + " 离开了房间");
                    refreshRoomInfo();
                } else if ("STARTED".equals(action)) {
                    displaySystemMessage("游戏已开始");
                    refreshRoomInfo();
                } else if ("ENDED".equals(action)) {
                    displaySystemMessage("游戏已结束");
                    refreshRoomInfo();
                }
            });
        }
    }

    private void handleRoomMessage(Map<String, Object> messageData) {
        String message = (String) messageData.get("message");
        String sender = (String) messageData.get("sender");
        Long timestamp = messageData.get("timestamp") instanceof Number
                ? ((Number) messageData.get("timestamp")).longValue()
                : System.currentTimeMillis();

        Platform.runLater(() -> {
            addMessageToChat(sender, message, timestamp);
        });
    }

    private void handleSystemNotification(Map<String, Object> notificationData) {
        String message = (String) notificationData.get("message");
        if (message != null) {
            Platform.runLater(() -> {
                displaySystemMessage(message);
            });
        }
    }

    private void loadRoomMessages(Long roomId) {
        executeAsync(() -> {
            try {
                // 使用修复后的方法获取房间消息
                List<Message> messages = messageApiService.getRoomMessages(roomId);

                Platform.runLater(() -> {
                    chatMessagesBox.getChildren().clear();

                    if (messages != null && !messages.isEmpty()) {
                        for (Message msg : messages) {
                            addMessageToChat(
                                    msg.getSender(),
                                    msg.getMessage(),
                                    msg.getTimestamp());
                        }
                    }
                });
            } catch (Exception e) {
                logger.error("加载房间消息出错", e);
                Platform.runLater(() -> {
                    displaySystemMessage("无法加载历史消息: " + e.getMessage());
                });
            }
        });
    }

    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty() || currentRoom == null)
            return;

        // 添加更严格的检查
        if (currentRoom == null || currentRoom.getId() == null) {
            AlertHelper.showError("发送失败", null, "房间信息无效，请尝试重新加入房间");
            return;
        }

        executeAsync(() -> {
            try {
                messageApiService.sendRoomMessage(currentRoom.getId(), message);
                runOnFXThread(() -> messageField.clear());
            } catch (Exception e) {
                logger.error("发送消息失败", e);
                runOnFXThread(() -> AlertHelper.showError("发送失败", null, "无法发送消息: " + e.getMessage()));
            }
        });
    }

    private void startGame() {
        if (currentRoom == null)
            return;

        // 检查当前状态
        if ("PLAYING".equals(currentRoom.getStatus())) {
            // 如果游戏已经开始，则结束游戏
            endGame();
            return;
        }

        executeAsync(() -> {
            try {
                startGameButton.setDisable(true);
                Room updatedRoom = roomApiService.startGame(currentRoom.getId());
                runOnFXThread(() -> {
                    if (updatedRoom != null) {
                        updateRoomInfo(updatedRoom);
                        displaySystemMessage("游戏已开始");
                    }
                    startGameButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("开始游戏失败", e);
                runOnFXThread(() -> {
                    AlertHelper.showError("开始失败", null, "无法开始游戏: " + e.getMessage());
                    startGameButton.setDisable(false);
                });
            }
        });
    }

    private void endGame() {
        if (currentRoom == null)
            return;

        executeAsync(() -> {
            try {
                startGameButton.setDisable(true);
                Room updatedRoom = roomApiService.endGame(currentRoom.getId());
                runOnFXThread(() -> {
                    if (updatedRoom != null) {
                        updateRoomInfo(updatedRoom);
                        displaySystemMessage("游戏已结束");
                    }
                    startGameButton.setDisable(false);
                });
            } catch (Exception e) {
                logger.error("结束游戏失败", e);
                runOnFXThread(() -> {
                    AlertHelper.showError("结束失败", null, "无法结束游戏: " + e.getMessage());
                    startGameButton.setDisable(false);
                });
            }
        });
    }

    private void leaveRoom() {
        executeAsync(() -> {
            try {
                boolean success = roomApiService.leaveRoom();
                runOnFXThread(() -> {
                    if (success) {
                        returnToLobby();
                    } else {
                        AlertHelper.showError("离开失败", null, "无法离开房间");
                    }
                });
            } catch (Exception e) {
                logger.error("离开房间失败", e);
                runOnFXThread(() -> AlertHelper.showError("离开失败", null, "无法离开房间: " + e.getMessage()));
            }
        });
    }

    private void returnToLobby() {
        // 清理资源
        cleanup();
        // 返回大厅
        stageManager.switchScene(FxmlView.LOBBY);
    }

    private void showNetworkInfo() {
        if (currentRoom == null)
            return;

        StringBuilder info = new StringBuilder();
        info.append("房间虚拟网络信息\n\n");
        info.append("网络ID: ").append(currentRoom.getNetworkId()).append("\n");
        info.append("网络名称: ").append(currentRoom.getNetworkName()).append("\n");
        info.append("网络类型: ").append(currentRoom.getNetworkType()).append("\n\n");

        User currentUser = sessionManager.getCurrentUser();
        if (currentUser != null) {
            info.append("您的虚拟IP: ").append(currentUser.getVirtualIp()).append("\n");
        }

        AlertHelper.showInformation("网络连接信息", null, info.toString());
    }

    private void showHelpDialog() {
        AlertHelper.showInformation("房间帮助", null,
                "欢迎使用游戏房间\n\n" +
                        "• 房主可以开始或结束游戏\n" +
                        "• 使用底部聊天框与其他玩家交流\n" +
                        "• 点击\"连接信息\"查看网络详情\n" +
                        "• 点击开始后即可自动进入虚拟局域网\n" +
                        "• 离开房间将返回大厅");
    }

    private void displaySystemMessage(String message) {
        addMessageToChat("系统", message, System.currentTimeMillis());
    }

    private void addMessageToChat(String sender, String message, long timestamp) {
        HBox messageContainer = new HBox(5);
        messageContainer.setMaxWidth(Double.MAX_VALUE);
        messageContainer.setPadding(new Insets(5));

        // 时间戳
        LocalDateTime time = timestamp > 0 ? LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp),
                java.time.ZoneId.systemDefault()) : LocalDateTime.now();
        String timeString = "[" + time.format(TIME_FORMATTER) + "] ";

        Text timeText = new Text(timeString);
        timeText.setFill(Color.GRAY);

        // 发送者
        Text senderText = new Text(sender + ": ");
        senderText.setFill(Color.BLUE);

        // 系统消息使用不同颜色
        if ("系统".equals(sender)) {
            senderText.setFill(Color.RED);
        }

        // 消息内容
        Text messageText = new Text(message);
        messageText.setFill(Color.BLACK);

        // 组合消息元素
        TextFlow textFlow = new TextFlow(timeText, senderText, messageText);
        textFlow.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(textFlow, Priority.ALWAYS);

        messageContainer.getChildren().add(textFlow);
        chatMessagesBox.getChildren().add(messageContainer);
    }

    private ListCell<String> createPlayerListCell(ListView<String> listView) {
        return new ListCell<String>() {
            @Override
            protected void updateItem(String player, boolean empty) {
                super.updateItem(player, empty);

                if (empty || player == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox container = new HBox(5);
                    container.setAlignment(Pos.CENTER_LEFT);

                    // 玩家状态指示器
                    Region statusIndicator = new Region();
                    statusIndicator.setPrefSize(10, 10);
                    statusIndicator.setMaxSize(10, 10);
                    statusIndicator.setMinSize(10, 10);
                    statusIndicator.setStyle("-fx-background-color: #4CAF50; -fx-background-radius: 5;");

                    // 玩家名称
                    Label nameLabel = new Label(player);
                    nameLabel.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(nameLabel, Priority.ALWAYS);

                    container.getChildren().addAll(statusIndicator, nameLabel);
                    setGraphic(container);
                }
            }
        };
    }

    private String formatRoomStatus(String status) {
        if (status == null)
            return "未知";

        switch (status) {
            case "WAITING":
                return "等待中";
            case "PLAYING":
                return "游戏中";
            case "FINISHED":
                return "已结束";
            default:
                return status;
        }
    }

    public void cleanup() {
        // 取消定时任务
        if (refreshTimer != null) {
            refreshTimer.cancel();
            refreshTimer = null;
        }

        // 取消所有订阅
        if (currentRoom != null) {
            webSocketService.unsubscribe("/topic/room." + currentRoom.getId() + ".messages");
            webSocketService.unsubscribe("/topic/rooms.updates");
            webSocketService.unsubscribe("/user/queue/notifications");
        }

        logger.info("已清理房间控制器资源和订阅");
    }

    // 在窗口关闭时调用，确保用户退出房间
    public void handleWindowClose() {
        leaveRoom();
    }
}