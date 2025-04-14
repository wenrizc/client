package com.client.controller;

import com.client.model.Message;
import com.client.model.Room;
import com.client.model.User;
import com.client.service.NetworkStatusService;
import com.client.service.WebSocketService;
import com.client.service.api.MessageApiService;
import com.client.service.api.RoomApiService;
import com.client.service.api.UserApiService;
import com.client.session.SessionManager;
import com.client.util.AlertHelper;
import com.client.view.FxmlView;
import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Controller
public class LobbyController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LobbyController.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML private TabPane tabPane;
    @FXML private Tab chatTab;
    @FXML private Tab roomsTab;
    @FXML private Tab profileTab;
    @FXML private ListView<User> userListView;
    @FXML private Button refreshButton;
    @FXML private Button logoutButton;
    @FXML private Label userNameLabel;
    @FXML private Label onlineCountLabel;
    @FXML private Label connectionStatusLabel;
    @FXML private Label onlineUsersStatusLabel;
    @FXML private Label roomsStatusLabel;
    @FXML private Label versionLabel;
    @FXML private Label userIdLabel;
    @FXML private Label profileUsernameLabel;
    @FXML private Label clientAddressLabel;
    @FXML private Label virtualIpLabel;
    @FXML private JFXButton refreshProfileButton;
    @FXML private JFXButton helpButton;
    @FXML private VBox chatMessagesBox;
    @FXML private ScrollPane chatScrollPane;
    @FXML private TextField messageField;
    @FXML private JFXButton sendButton;
    @FXML private TableView<Room> roomsTableView;
    @FXML private TableColumn<Room, String> roomNameColumn;
    @FXML private TableColumn<Room, String> gameNameColumn;
    @FXML private TableColumn<Room, String> playerCountColumn;
    @FXML private TableColumn<Room, String> roomStatusColumn;
    @FXML private TableColumn<Room, Button> actionColumn;
    @FXML private JFXButton refreshRoomsButton;
    @FXML private JFXButton createRoomButton;

    @Autowired private UserApiService userApiService;
    @Autowired private RoomApiService roomApiService;
    @Autowired private MessageApiService messageApiService;
    @Autowired private NetworkStatusService networkStatusService;
    @Autowired private WebSocketService webSocketService;
    @Autowired private SessionManager sessionManager;

    private ObservableList<Room> roomsList = FXCollections.observableArrayList();
    private ObservableList<User> usersList = FXCollections.observableArrayList();
    private ScheduledExecutorService scheduledExecutorService;
    private Map<String, User> usersMap = new HashMap<>();

    @Override
    public void initialize() {
        logger.info("初始化大厅控制器");

        // 设置版本标签
        versionLabel.setText("v" + appProperties.getVersion());

        // 初始化用户信息
        updateCurrentUserInfo();

        // 设置按钮事件
        refreshButton.setOnAction(event -> refreshUsersList());
        logoutButton.setOnAction(event -> handleLogout());
        sendButton.setOnAction(event -> sendMessage());
        messageField.setOnAction(event -> sendMessage());
        refreshProfileButton.setOnAction(event -> refreshProfileInfo());
        helpButton.setOnAction(event -> showHelpDialog());
        refreshRoomsButton.setOnAction(event -> refreshRoomsList());
        createRoomButton.setOnAction(event -> showCreateRoomDialog());

        // 设置标签页切换监听
        tabPane.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldTab, newTab) -> handleTabChange(newTab));

        // 初始化用户列表单元格工厂
        setupUserListView();

        // 初始化房间表格
        setupRoomsTable();

        // 自动滚动聊天窗口
        chatMessagesBox.heightProperty().addListener((observable, oldValue, newValue) -> {
            chatScrollPane.setVvalue(1.0);
        });

        // 初始化WebSocket连接及订阅
        initWebSocketConnection();

        // 立即加载数据
        refreshUsersList();
        refreshRoomsList();
        loadLobbyMessages();

        // 设置定时刷新任务
        setupPeriodicRefresh();
    }

    /**
     * 设置WebSocket连接和订阅
     */
    private void initWebSocketConnection() {
        // 确保WebSocket连接
        if (!webSocketService.isConnected()) {
            boolean connected = webSocketService.connect();
            if (connected) {
                logger.info("WebSocket连接已建立");
                subscribeToTopics();
            } else {
                logger.warn("WebSocket连接失败，无法订阅主题");
                // 可以添加重新连接逻辑或在UI上显示错误信息
            }
        } else {
            subscribeToTopics();
        }
    }

    /**
     * 订阅WebSocket主题
     */
    private void subscribeToTopics() {
        // 订阅大厅消息
        webSocketService.subscribeLobbyMessages(message -> {
            displayChatMessage((Map<String, Object>) message);
        });

        // 订阅用户状态更新
        webSocketService.subscribeUserStatus(this::handleUserStatusUpdate);

        // 订阅房间更新
        webSocketService.subscribeRoomUpdates(this::handleRoomUpdate);

        // 订阅系统通知
        webSocketService.subscribeSystemNotifications(this::displaySystemNotification);

        // 订阅个人消息
        webSocketService.subscribePersonalMessages(this::handlePersonalMessage);

        // 订阅错误消息
        webSocketService.subscribeErrors(this::displayErrorMessage);
    }
    /**
     * 设置定时刷新任务
     */
    private void setupPeriodicRefresh() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        // 每30秒刷新一次用户列表
        scheduledExecutorService.scheduleAtFixedRate(() -> Platform.runLater(this::refreshUsersList),
                30, 30, TimeUnit.SECONDS);
        // 每60秒刷新一次房间列表
        scheduledExecutorService.scheduleAtFixedRate(() -> Platform.runLater(this::refreshRoomsList),
                60, 60, TimeUnit.SECONDS);
    }

    /**
     * 设置用户列表视图
     */
    private void setupUserListView() {
        userListView.setCellFactory(listView -> new ListCell<User>() {
            @Override
            protected void updateItem(User user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    HBox container = new HBox(5);
                    container.setAlignment(Pos.CENTER_LEFT);

                    // 用户状态指示器
                    Region statusIndicator = new Region();
                    statusIndicator.setPrefSize(10, 10);
                    statusIndicator.setMaxSize(10, 10);
                    statusIndicator.setMinSize(10, 10);

                    if (user.isActive()) {
                        statusIndicator.setStyle("-fx-background-color: #4CAF50; -fx-background-radius: 5;");
                    } else {
                        statusIndicator.setStyle("-fx-background-color: #9E9E9E; -fx-background-radius: 5;");
                    }

                    Label nameLabel = new Label(user.getUsername());
                    nameLabel.setMaxWidth(Double.MAX_VALUE);
                    HBox.setHgrow(nameLabel, Priority.ALWAYS);

                    container.getChildren().addAll(statusIndicator, nameLabel);
                    setGraphic(container);
                }
            }
        });

        userListView.setItems(usersList);
    }

    /**
     * 设置房间表格
     */
    private void setupRoomsTable() {
        roomNameColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getName()));

        gameNameColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getGameName()));

        playerCountColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(cellData.getValue().getPlayers().size() + "/" +
                        cellData.getValue().getMaxPlayers()));

        roomStatusColumn.setCellValueFactory(cellData ->
                new SimpleStringProperty(formatRoomStatus(cellData.getValue().getStatus())));

        actionColumn.setCellValueFactory(cellData -> {
            Room room = cellData.getValue();
            JFXButton joinButton = new JFXButton("加入");
            joinButton.getStyleClass().add("button-small");

            if (room.getStatus().equals("PLAYING") || room.isFull()) {
                joinButton.setDisable(true);
            } else {
                joinButton.setOnAction(event -> joinRoom(room));
            }

            return new SimpleObjectProperty<>(joinButton);
        });

        roomsTableView.setItems(roomsList);
    }

    /**
     * 格式化房间状态
     */
    private String formatRoomStatus(String status) {
        if (status == null) return "未知";

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

    /**
     * 更新当前用户信息
     */
    private void updateCurrentUserInfo() {
        try {
            User currentUser = sessionManager.getCurrentUser();
            if (currentUser != null) {
                userNameLabel.setText("用户: " + currentUser.getUsername());
                updateProfileInfo(currentUser);
            }
        } catch (Exception e) {
            logger.error("更新用户信息出错", e);
        }
    }

    /**
     * 刷新个人信息
     */
    private void refreshProfileInfo() {
        try {
            User currentUser = userApiService.getCurrentUser();
            updateProfileInfo(currentUser);
            showInfoAlert("刷新成功", "个人信息已更新");
        } catch (Exception e) {
            logger.error("刷新个人信息出错", e);
            showErrorAlert("刷新失败", "无法获取最新个人信息: " + e.getMessage());
        }
    }

    /**
     * 更新个人信息显示
     */
    private void updateProfileInfo(User user) {
        if (user != null) {
            userIdLabel.setText(user.getId().toString());
            profileUsernameLabel.setText(user.getUsername());

            try {
                String localIp = InetAddress.getLocalHost().getHostAddress();
                clientAddressLabel.setText(localIp);
            } catch (Exception e) {
                clientAddressLabel.setText("无法获取");
                logger.error("获取本地IP地址出错", e);
            }

            virtualIpLabel.setText(user.getVirtualIp() != null ? user.getVirtualIp() : "未分配");
        }
    }

    /**
     * 刷新用户列表
     */
    private void refreshUsersList() {
        try {
            List<User> users = userApiService.getAllActiveUsers();
            usersList.clear();
            usersMap.clear();

            if (users != null) {
                for (User user : users) {
                    usersList.add(user);
                    usersMap.put(user.getUsername(), user);
                }

                // 更新计数
                onlineCountLabel.setText("(" + users.size() + ")");
                onlineUsersStatusLabel.setText("在线用户: " + users.size());
            }
        } catch (Exception e) {
            logger.error("刷新用户列表出错", e);
        }
    }

    /**
     * 刷新房间列表
     */
    private void refreshRoomsList() {
        try {
            List<Room> rooms = roomApiService.getRoomList();
            roomsList.clear();

            if (rooms != null) {
                roomsList.addAll(rooms);
                roomsStatusLabel.setText("可用房间: " + rooms.size());
            }
        } catch (Exception e) {
            logger.error("刷新房间列表出错", e);
        }
    }

    /**
     * 加载大厅历史消息
     */
    private void loadLobbyMessages() {
        try {
            List<Message> messages = messageApiService.getLobbyMessageHistory();
            if (messages != null) {
                chatMessagesBox.getChildren().clear();
                for (Message message : messages) {
                    addMessageToChat(message.getSender(), message.getMessage(), message.getTimestamp());
                }
            }
        } catch (Exception e) {
            logger.error("加载聊天历史出错", e);
            displaySystemMessage("无法加载聊天历史");
        }
    }

    /**
     * 处理发送消息
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) return;

        try {
            boolean sent = messageApiService.sendLobbyMessage(message);
            if (sent) {
                messageField.clear();
            } else {
                showErrorAlert("发送失败", "消息发送失败，请重试");
            }
        } catch (Exception e) {
            logger.error("发送消息出错", e);
            showErrorAlert("发送失败", "消息发送出错: " + e.getMessage());
        }
    }

    /**
     * 添加消息到聊天区域
     */
    private void addMessageToChat(String sender, String message, long timestamp) {
        HBox messageContainer = new HBox(5);
        messageContainer.setMaxWidth(Double.MAX_VALUE);
        messageContainer.setPadding(new Insets(5));

        // 时间戳
        LocalDateTime time = timestamp > 0 ?
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp),
                        java.time.ZoneId.systemDefault()) :
                LocalDateTime.now();
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

        TextFlow textFlow = new TextFlow(timeText, senderText, messageText);
        textFlow.setMaxWidth(Double.MAX_VALUE);

        messageContainer.getChildren().add(textFlow);

        Platform.runLater(() -> {
            chatMessagesBox.getChildren().add(messageContainer);
        });
    }

    /**
     * 显示系统消息
     */
    private void displaySystemMessage(String message) {
        addMessageToChat("系统", message, System.currentTimeMillis());
    }

    /**
     * 显示聊天消息(从WebSocket接收)
     */
    private void displayChatMessage(Map<String, Object> messageData) {
        String sender = (String) messageData.get("sender");
        String message = (String) messageData.get("message");
        Long timestamp = (Long) messageData.get("timestamp");

        if (sender != null && message != null) {
            Platform.runLater(() -> {
                addMessageToChat(sender, message, timestamp != null ? timestamp : System.currentTimeMillis());
            });
        }
    }

    /**
     * 处理用户状态更新
     */
    private void handleUserStatusUpdate(Map<String, Object> statusData) {
        String username = (String) statusData.get("username");
        Boolean active = (Boolean) statusData.get("active");
        String action = (String) statusData.get("action");

        if (username != null && action != null) {
            Platform.runLater(() -> {
                // 更新用户列表
                refreshUsersList();

                // 显示系统通知
                if ("CONNECTED".equals(action)) {
                    displaySystemMessage("用户 \"" + username + "\" 已上线");
                } else if ("DISCONNECTED".equals(action)) {
                    displaySystemMessage("用户 \"" + username + "\" 已离线");
                }
            });
        }
    }

    /**
     * 处理房间更新
     */
    private void handleRoomUpdate(Map<String, Object> roomData) {
        Platform.runLater(() -> {
            refreshRoomsList();

            String action = (String) roomData.get("action");
            String username = (String) roomData.get("username");

            if (action != null && username != null) {
                switch(action) {
                    case "CREATED":
                        displaySystemMessage("用户 \"" + username + "\" 创建了新房间");
                        break;
                    case "JOINED":
                        // 不在大厅显示加入消息，避免消息过多
                        break;
                    case "LEFT":
                        // 不在大厅显示离开消息
                        break;
                    case "STARTED":
                        displaySystemMessage("房间游戏已开始");
                        break;
                    case "ENDED":
                        displaySystemMessage("房间游戏已结束");
                        break;
                }
            }
        });
    }

    /**
     * 显示系统通知
     */
    private void displaySystemNotification(Map<String, Object> notificationData) {
        String message = (String) notificationData.get("message");
        if (message != null) {
            Platform.runLater(() -> {
                displaySystemMessage(message);
            });
        }
    }

    /**
     * 处理个人消息
     */
    private void handlePersonalMessage(Map<String, Object> messageData) {
        String type = (String) messageData.get("type");

        if ("ROOM_CREATED".equals(type)) {
            Platform.runLater(() -> {
                refreshRoomsList();
                showInfoAlert("创建成功", "房间已成功创建");
            });
        }
    }

    /**
     * 显示错误消息
     */
    private void displayErrorMessage(Map<String, Object> errorData) {
        String error = (String) errorData.get("error");
        if (error != null) {
            Platform.runLater(() -> {
                showErrorAlert("错误", error);
            });
        }
    }

    /**
     * 处理标签页切换
     */
    private void handleTabChange(Tab newTab) {
        if (newTab == roomsTab) {
            logger.debug("切换到房间列表标签页");
            refreshRoomsList();
        } else if (newTab == chatTab) {
            logger.debug("切换到聊天标签页");
        } else if (newTab == profileTab) {
            logger.debug("切换到个人信息标签页");
            updateCurrentUserInfo();
        }
    }

    /**
     * 加入房间
     */
    private void joinRoom(Room room) {
        if (room == null) return;

        try {
            Room joinedRoom = roomApiService.joinRoom(room.getId());
            if (joinedRoom != null) {
                logger.info("成功加入房间: {}", joinedRoom.getName());
                stageManager.switchScene(FxmlView.ROOM);
            }
        } catch (Exception e) {
            logger.error("加入房间出错", e);
            showErrorAlert("加入失败", "无法加入房间: " + e.getMessage());
        }
    }

    /**
     * 显示创建房间对话框
     */
    private void showCreateRoomDialog() {
        stageManager.openDialog(FxmlView.CREATE_ROOM);
        refreshRoomsList();
    }

    /**
     * 显示帮助对话框
     */
    private void showHelpDialog() {
        AlertHelper.showInformation(
                "游戏大厅帮助",
                "游戏大厅使用指南",
                "1. 大厅聊天：与所有在线用户交流\n" +
                        "2. 游戏房间：查看、创建或加入游戏房间\n" +
                        "3. 个人信息：查看您的账户详情\n\n" +
                        "如需更多帮助，请联系客服。"
        );
    }

    /**
     * 处理登出
     */
    private void handleLogout() {
        try {
            userApiService.logout();
        } catch (Exception e) {
            logger.error("登出时出错", e);
        } finally {
            webSocketService.disconnect();
            if (scheduledExecutorService != null) {
                scheduledExecutorService.shutdown();
            }
            stageManager.switchScene(FxmlView.LOGIN);
        }
    }

    /**
     * 控制器销毁时调用
     */
    public void cleanup() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
        }
    }
}