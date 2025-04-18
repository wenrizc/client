package com.client.controller;

import com.client.model.Message;
import com.client.model.Room;
import com.client.model.User;
import com.client.service.*;
import com.client.util.AlertHelper;
import com.client.util.SessionManager;
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

/**
 * 游戏大厅控制器
 * 负责处理游戏大厅的用户界面交互与业务逻辑
 */
@Controller
public class LobbyController extends BaseController {

    private static final Logger logger = LoggerFactory.getLogger(LobbyController.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    @FXML private TabPane tabPane;
    @FXML private Tab chatTab;
    @FXML private Tab roomsTab;
    @FXML private Tab profileTab;
    @FXML private ListView<User> userListView;
    @FXML private Label onlineCountLabel;
    @FXML private Label onlineUsersStatusLabel;
    @FXML private Button refreshButton;
    @FXML private Button logoutButton;
    @FXML private Label userNameLabel;
    @FXML private Label connectionStatusLabel;
    @FXML private Label versionLabel;
    @FXML private JFXButton helpButton;
    @FXML private Label userIdLabel;
    @FXML private Label profileUsernameLabel;
    @FXML private Label clientAddressLabel;
    @FXML private Label virtualIpLabel;
    @FXML private JFXButton refreshProfileButton;
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
    @FXML private Label roomsStatusLabel;

    @Autowired private UserApiService userApiService;
    @Autowired private RoomApiService roomApiService;
    @Autowired private MessageApiService messageApiService;
    @Autowired private NetworkStatusService networkStatusService;
    @Autowired private WebSocketService webSocketService;
    @Autowired private SessionManager sessionManager;

    private ObservableList<Room> roomsList = FXCollections.observableArrayList();
    private ObservableList<User> usersList = FXCollections.observableArrayList();
    private Map<String, User> usersMap = new HashMap<>();

    // 后台任务
    private ScheduledExecutorService scheduledExecutorService;

    /**
     * 初始化控制器
     */
    @Override
    public void initialize() {
        logger.info("初始化大厅控制器");
        versionLabel.setText("v" + appProperties.getVersion());

        bindEventHandlers();
        setupUserListView();
        setupRoomsTable();
        setupChatScrolling();

        tabPane.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldTab, newTab) -> handleTabChange(newTab));

        updateCurrentUserInfo();
        initWebSocketConnection();
        refreshUsersList();
        refreshRoomsList();
        loadLobbyMessages();
        setupPeriodicRefresh();
    }

    /**
     * 绑定事件处理器
     */
    private void bindEventHandlers() {
        refreshButton.setOnAction(event -> refreshUsersList());
        logoutButton.setOnAction(event -> handleLogout());
        sendButton.setOnAction(event -> sendMessage());
        messageField.setOnAction(event -> sendMessage());
        refreshProfileButton.setOnAction(event -> refreshProfileInfo());
        helpButton.setOnAction(event -> showHelpDialog());
        refreshRoomsButton.setOnAction(event -> refreshRoomsList());
        createRoomButton.setOnAction(event -> showCreateRoomDialog());
    }

    /**
     * 设置聊天窗口自动滚动
     */
    private void setupChatScrolling() {
        chatMessagesBox.heightProperty().addListener((observable, oldValue, newValue) ->
                chatScrollPane.setVvalue(1.0)
        );
    }

    /**
     * 设置定时刷新任务
     */
    private void setupPeriodicRefresh() {
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService.scheduleAtFixedRate(
                () -> runOnFXThread(this::refreshUsersList),
                30, 30, TimeUnit.SECONDS);
        scheduledExecutorService.scheduleAtFixedRate(
                () -> runOnFXThread(this::refreshRoomsList),
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
                    return;
                }

                HBox container = new HBox(5);
                container.setAlignment(Pos.CENTER_LEFT);

                Region statusIndicator = new Region();
                statusIndicator.setPrefSize(10, 10);
                statusIndicator.setMaxSize(10, 10);
                statusIndicator.setMinSize(10, 10);
                statusIndicator.setStyle("-fx-background-color: " +
                        (user.isActive() ? "#4CAF50" : "#9E9E9E") +
                        "; -fx-background-radius: 5;");

                Label nameLabel = new Label(user.getUsername());
                nameLabel.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(nameLabel, Priority.ALWAYS);

                container.getChildren().addAll(statusIndicator, nameLabel);
                setGraphic(container);
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
     * 刷新用户列表数据
     */
    private void refreshUsersList() {
        try {
            List<User> users = userApiService.getAllActiveUsers();
            usersList.clear();
            usersMap.clear();

            if (users != null) {
                users.forEach(user -> {
                    usersList.add(user);
                    usersMap.put(user.getUsername(), user);
                });

                onlineCountLabel.setText("(" + users.size() + ")");
                onlineUsersStatusLabel.setText("在线用户: " + users.size());
            }
        } catch (Exception e) {
            logger.error("刷新用户列表出错", e);
        }
    }

    /**
     * 刷新房间列表数据
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
     * 刷新个人资料信息
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
        if (user == null) return;

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


    /**
     * 处理标签页切换
     */
    private void handleTabChange(Tab newTab) {
        if (newTab == roomsTab) {
            logger.debug("切换到房间列表标签页");
            refreshRoomsList();
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
                        "3. 个人信息：查看您的账户详情\n\n"
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
     * 初始化WebSocket连接
     */
    private void initWebSocketConnection() {
        if (!webSocketService.isConnected()) {
            boolean connected = webSocketService.connect();
            if (connected) {
                logger.info("WebSocket连接已建立");
                subscribeToTopics();
            } else {
                logger.warn("WebSocket连接失败，无法订阅主题");
            }
        } else {
            subscribeToTopics();
        }
    }

    /**
     * 订阅WebSocket主题
     */
    private void subscribeToTopics() {
        webSocketService.subscribeLobbyMessages(message -> {
            try {
                logger.debug("收到大厅消息: {}", message);
                if (message instanceof Map) {
                    displayChatMessage((Map<String, Object>) message);
                } else {
                    logger.error("收到了意外类型的大厅消息: {}", message.getClass().getName());
                }
            } catch (Exception e) {
                logger.error("处理大厅消息时出错", e);
            }
        });

        webSocketService.subscribeUserStatus(this::handleUserStatusUpdate);
        webSocketService.subscribeRoomUpdates(this::handleRoomUpdate);
        webSocketService.subscribeSystemNotifications(this::displaySystemNotification);
        webSocketService.subscribePersonalMessages(this::handlePersonalMessage);
        webSocketService.subscribeErrors(this::displayErrorMessage);
    }

    /**
     * 处理WebSocket接收的用户状态更新
     */
    private void handleUserStatusUpdate(Map<String, Object> statusData) {
        String username = (String) statusData.get("username");
        String action = (String) statusData.get("action");

        if (username != null && action != null) {
            runOnFXThread(() -> {
                refreshUsersList();

                if ("CONNECTED".equals(action)) {
                    displaySystemMessage("用户 \"" + username + "\" 已上线");
                } else if ("DISCONNECTED".equals(action)) {
                    displaySystemMessage("用户 \"" + username + "\" 已离线");
                }
            });
        }
    }

    /**
     * 处理WebSocket接收的房间更新
     */
    private void handleRoomUpdate(Map<String, Object> roomData) {
        runOnFXThread(() -> {
            refreshRoomsList();

            String action = (String) roomData.get("action");
            String username = (String) roomData.get("username");

            if (action != null && username != null) {
                switch(action) {
                    case "CREATED":
                        displaySystemMessage("用户 \"" + username + "\" 创建了新房间");
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
     * 处理WebSocket接收的系统通知
     */
    private void displaySystemNotification(Map<String, Object> notificationData) {
        String message = (String) notificationData.get("message");
        if (message != null) {
            runOnFXThread(() -> displaySystemMessage(message));
        }
    }

    /**
     * 处理WebSocket接收的个人消息
     */
    private void handlePersonalMessage(Map<String, Object> messageData) {
        String type = (String) messageData.get("type");

        if ("ROOM_CREATED".equals(type)) {
            runOnFXThread(() -> {
                refreshRoomsList();
                showInfoAlert("创建成功", "房间已成功创建");
            });
        }
    }

    /**
     * 处理WebSocket接收的错误消息
     */
    private void displayErrorMessage(Map<String, Object> errorData) {
        String error = (String) errorData.get("error");
        if (error != null) {
            runOnFXThread(() -> showErrorAlert("错误", error));
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
                messages.forEach(message ->
                        addMessageToChat(message.getSender(), message.getMessage(), message.getTimestamp())
                );
            }
        } catch (Exception e) {
            logger.error("加载聊天历史出错", e);
            displaySystemMessage("无法加载聊天历史");
        }
    }

    /**
     * 处理消息发送
     */
    private void sendMessage() {
        String message = messageField.getText().trim();
        if (message.isEmpty()) return;

        // 获取当前用户名
        String currentUsername = sessionManager.getCurrentUser().getUsername();
        // 先在本地显示消息
        long timestamp = System.currentTimeMillis();
        runOnFXThread(() -> {
            addMessageToChat(currentUsername, message, timestamp);
            messageField.clear();
        });

        // 然后发送到服务器
        executeAsync(() -> {
            try {
                messageApiService.sendLobbyMessage(message);
            } catch (Exception e) {
                logger.error("发送消息出错", e);
                showErrorAlert("发送失败", "消息发送出错: " + e.getMessage());
            }
        });
    }


    /**
     * 添加消息到聊天区域
     */
    private void addMessageToChat(String sender, String message, long timestamp) {
        HBox messageContainer = new HBox(5);
        messageContainer.setMaxWidth(Double.MAX_VALUE);
        messageContainer.setPadding(new Insets(5));

        LocalDateTime time = timestamp > 0 ?
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(timestamp),
                        java.time.ZoneId.systemDefault()) :
                LocalDateTime.now();
        String timeString = "[" + time.format(TIME_FORMATTER) + "] ";

        Text timeText = new Text(timeString);
        timeText.setFill(Color.GRAY);

        Text senderText = new Text(sender + ": ");
        senderText.setFill("系统".equals(sender) ? Color.RED : Color.BLUE);

        Text messageText = new Text(message);
        messageText.setFill(Color.BLACK);

        TextFlow textFlow = new TextFlow(timeText, senderText, messageText);
        textFlow.setMaxWidth(Double.MAX_VALUE);

        messageContainer.getChildren().add(textFlow);
        runOnFXThread(() -> chatMessagesBox.getChildren().add(messageContainer));
    }

    /**
     * 显示系统消息
     */
    private void displaySystemMessage(String message) {
        addMessageToChat("系统", message, System.currentTimeMillis());
    }

    /**
     * 处理WebSocket接收的聊天消息
     */
    private void displayChatMessage(Map<String, Object> messageData) {
        String sender = (String) messageData.get("sender");
        String message = (String) messageData.get("message");
        Long timestamp = (Long) messageData.get("timestamp");

        // 检查发送者是否是当前用户
        String currentUsername = sessionManager.getCurrentUser() != null ?
                sessionManager.getCurrentUser().getUsername() : null;

        // 如果不是自己发的消息才显示，避免重复
        if (sender != null && message != null && (currentUsername == null || !currentUsername.equals(sender))) {
            Platform.runLater(() ->
                    addMessageToChat(sender, message, timestamp != null ? timestamp : System.currentTimeMillis())
            );
        }
    }

    /**
     * 格式化房间状态显示文本
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
     * 控制器销毁时调用
     */
    public void cleanup() {
        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdown();
        }
    }
}