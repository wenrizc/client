package com.client.ui;

import com.client.Main;
import com.client.config.AppConfig;
import com.client.model.Message;
import com.client.model.Room;
import com.client.model.User;
import com.client.service.ApiService;
import com.client.service.WebSocketService;
import com.client.util.AlertUtil;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.*;

public class MainView extends BorderPane {

    private final ApiService apiService;
    private final WebSocketService webSocketService;
    private final AppConfig appConfig;
    private final ObservableList<User> activeUsers = FXCollections.observableArrayList();
    private final Timer refreshTimer = new Timer();
    private User currentUser;
    private Label statusLabel;
    private ListView<User> userListView;

    public MainView(User currentUser, ApiService apiService, AppConfig appConfig) {
        this.currentUser = currentUser;
        this.apiService = apiService;
        this.appConfig = appConfig;
        this.webSocketService = new WebSocketService(appConfig, currentUser.getUsername(), apiService);

        setPadding(new Insets(10));

        // 创建顶部菜单栏
        setTop(createMenuBar());

        // 创建左侧用户列表
        setLeft(createUserListView());

        // 创建中间内容区域
        setCenter(createContentArea());

        // 创建底部状态栏
        setBottom(createStatusBar());

        // 异步连接WebSocket和加载数据
        statusLabel.setText("正在连接到服务器...");
        new Thread(() -> {
            try {
                // 连接WebSocket
                webSocketService.connect();

                Platform.runLater(() -> {
                    statusLabel.setText("已连接到服务器");
                    // 在连接成功后开始定期刷新用户列表
                    startRefreshTimer();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("连接服务器失败: " + e.getMessage());
                    AlertUtil.showError("连接失败", "无法连接到WebSocket服务器: " + e.getMessage());
                });
            }
        }).start();
    }



    private MenuBar createMenuBar() {
        MenuBar menuBar = new MenuBar();

        // 文件菜单
        Menu fileMenu = new Menu("文件");
        MenuItem settingsItem = new MenuItem("设置");
        settingsItem.setOnAction(e -> showSettingsDialog());
        MenuItem logoutItem = new MenuItem("退出登录");
        logoutItem.setOnAction(e -> handleLogout());
        MenuItem exitItem = new MenuItem("退出程序");
        exitItem.setOnAction(e -> Platform.exit());
        fileMenu.getItems().addAll(settingsItem, new SeparatorMenuItem(), logoutItem, exitItem);

        // 帮助菜单
        Menu helpMenu = new Menu("帮助");
        MenuItem aboutItem = new MenuItem("关于");
        aboutItem.setOnAction(e -> showAboutDialog());
        helpMenu.getItems().add(aboutItem);

        menuBar.getMenus().addAll(fileMenu, helpMenu);
        return menuBar;
    }

    private VBox createUserListView() {
        VBox vbox = new VBox(10);
        vbox.setPadding(new Insets(10));
        vbox.setPrefWidth(200);

        Label titleLabel = new Label("在线用户");
        titleLabel.setStyle("-fx-font-weight: bold;");

        userListView = new ListView<>(activeUsers);
        userListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(User user, boolean empty) {
                super.updateItem(user, empty);
                if (empty || user == null) {
                    setText(null);
                } else {
                    setText(user.getUsername() + (user.isActive() ? " (在线)" : " (离线)"));
                    if (user.getUsername().equals(currentUser.getUsername())) {
                        setStyle("-fx-font-weight: bold;");
                    } else {
                        setStyle("");
                    }
                }
            }
        });

        Button refreshButton = new Button("刷新");
        refreshButton.setMaxWidth(Double.MAX_VALUE);
        refreshButton.setOnAction(e -> refreshUserList());

        vbox.getChildren().addAll(titleLabel, userListView, refreshButton);
        return vbox;
    }

    private TabPane createContentArea() {
        TabPane tabPane = new TabPane();

        // 大厅标签页 (修改为聊天室)
        Tab lobbyTab = new Tab("大厅聊天");
        lobbyTab.setClosable(false);
        lobbyTab.setContent(createLobbyChatView());

        // 房间列表标签页
        Tab roomsTab = new Tab("游戏房间");
        roomsTab.setClosable(false);
        roomsTab.setContent(createRoomsView());

        // 个人信息标签页
        Tab profileTab = new Tab("个人信息");
        profileTab.setClosable(false);
        profileTab.setContent(createProfileView());

        tabPane.getTabs().addAll(lobbyTab, roomsTab, profileTab);
        return tabPane;
    }


    // 修改createLobbyChatView方法

    private VBox createLobbyChatView() {
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
                    // 修改这里的消息显示格式，确保包含时间和用户名
                    setText(String.format("[%s] %s: %s",
                            message.getFormattedTime(), message.getSender(), message.getContent()));

                    // 根据消息发送者设置不同样式
                    if (message.getSender().equals("系统")) {
                        setStyle("-fx-text-fill: red;");
                    } else if (message.getSender().equals(currentUser.getUsername())) {
                        setStyle("-fx-text-fill: black; -fx-font-weight: black;");
                    } else {
                        setStyle("-fx-text-fill: black;");
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

        // 发送消息事件处理
        EventHandler<ActionEvent> sendAction = event -> {
            String message = messageField.getText().trim();
            if (!message.isEmpty()) {
                messageField.clear();
                webSocketService.sendLobbyMessage(message);

                // 为用户提供即时反馈，但实际显示应通过WebSocket响应
                statusLabel.setText("消息已发送");
            }
        };

        sendButton.setOnAction(sendAction);
        messageField.setOnAction(sendAction);

        // 加载历史消息
        loadLobbyMessages(chatListView);

        // 设置WebSocket消息处理器 - 确保消息正确添加到列表
        webSocketService.setLobbyMessageHandler(message -> {
            Platform.runLater(() -> {
                chatListView.getItems().add(message);
                chatListView.scrollTo(chatListView.getItems().size() - 1);
            });
        });

        chatBox.getChildren().addAll(chatListView, inputBox);
        return chatBox;
    }

    // 添加以下方法创建房间列表界面
    private VBox createRoomsView() {
        VBox roomsBox = new VBox(10);
        roomsBox.setPadding(new Insets(10));

        // 房间列表
        ListView<Room> roomListView = new ListView<>();
        roomListView.setCellFactory(param -> new ListCell<>() {
            @Override
            protected void updateItem(Room room, boolean empty) {
                super.updateItem(room, empty);
                if (empty || room == null) {
                    setText(null);
                } else {
                    setText(room.getName() + " [" + room.getPlayers().size() + "/" +
                            room.getMaxPlayers() + "] - " + room.getGameType());
                }
            }
        });
        VBox.setVgrow(roomListView, Priority.ALWAYS);

        // 操作按钮区域
        HBox buttonBox = new HBox(10);

        Button refreshButton = new Button("刷新列表");
        Button createButton = new Button("创建房间");
        Button joinButton = new Button("加入房间");

        buttonBox.getChildren().addAll(refreshButton, createButton, joinButton);
        buttonBox.setAlignment(Pos.CENTER);

        // 事件处理
        refreshButton.setOnAction(e -> loadRoomsList(roomListView));

        createButton.setOnAction(e -> showCreateRoomDialog());

        joinButton.setOnAction(e -> {
            Room selectedRoom = roomListView.getSelectionModel().getSelectedItem();
            if (selectedRoom != null) {
                joinRoom(selectedRoom.getId());
            } else {
                AlertUtil.showWarning("提示", "请先选择一个房间");
            }
        });

        // 初始加载房间列表
        loadRoomsList(roomListView);

        // 设置定时刷新
        Timer roomRefreshTimer = new Timer(true);
        roomRefreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> loadRoomsList(roomListView));
            }
        }, 10000, 10000); // 每10秒刷新一次

        roomsBox.getChildren().addAll(roomListView, buttonBox);
        return roomsBox;
    }

    // 添加方法创建个人信息界面
    private GridPane createProfileView() {
        GridPane profileGrid = new GridPane();
        profileGrid.setPadding(new Insets(20));
        profileGrid.setHgap(10);
        profileGrid.setVgap(10);

        profileGrid.add(new Label("用户ID:"), 0, 0);
        profileGrid.add(new Label(currentUser.getId().toString()), 1, 0);

        // 明确标识用户名字段
        Label usernameLabel = new Label(currentUser.getUsername());
        profileGrid.add(new Label("用户名:"), 0, 1);
        profileGrid.add(usernameLabel, 1, 1);

        // 客户端地址字段
        Label clientAddressLabel = new Label(currentUser.getClientAddress() != null ?
                currentUser.getClientAddress() : "未知");
        profileGrid.add(new Label("客户端地址:"), 0, 2);
        profileGrid.add(clientAddressLabel, 1, 2);

        // 虚拟IP字段
        Label virtualIpLabel = new Label(currentUser.getVirtualIp() != null ?
                currentUser.getVirtualIp() : "未分配");
        profileGrid.add(new Label("虚拟IP:"), 0, 3);
        profileGrid.add(virtualIpLabel, 1, 3);

        // 添加刷新按钮
        Button refreshButton = new Button("刷新个人信息");
        refreshButton.setOnAction(e -> refreshUserInfo(profileGrid, usernameLabel, clientAddressLabel, virtualIpLabel));
        profileGrid.add(refreshButton, 1, 5);

        return profileGrid;
    }

    // 添加加载大厅消息的方法
    private void loadLobbyMessages(ListView<Message> chatListView) {
        new Thread(() -> {
            try {
                List<Map<String, Object>> messageHistory = apiService.getLobbyMessages();
                List<Message> messages = new ArrayList<>();

                for (Map<String, Object> messageData : messageHistory) {
                    Message message = new Message(
                            (String) messageData.get("sender"),
                            (String) messageData.get("message"),
                            ((Number) messageData.get("timestamp")).longValue(),
                            (String) messageData.getOrDefault("type", "LOBBY_MESSAGE")
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
                        AlertUtil.showError("错误", "无法加载聊天记录: " + e.getMessage())
                );
            }
        }).start();
    }

    // 添加加载房间列表的方法
    private void loadRoomsList(ListView<Room> roomListView) {
        new Thread(() -> {
            try {
                List<Room> rooms = apiService.getJoinableRooms();
                Platform.runLater(() -> {
                    roomListView.getItems().clear();
                    roomListView.getItems().addAll(rooms);
                    statusLabel.setText("房间列表已更新 - 可用房间: " + rooms.size());
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        statusLabel.setText("无法获取房间列表: " + e.getMessage())
                );
            }
        }).start();
    }

    // 添加显示创建房间对话框的方法
    private void showCreateRoomDialog() {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("创建房间");
        dialog.setHeaderText("请输入房间信息");

        // 设置按钮
        ButtonType createButtonType = new ButtonType("创建", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButtonType, ButtonType.CANCEL);

        // 创建表单
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField roomNameField = new TextField();
        roomNameField.setPromptText("房间名称");

        ComboBox<String> gameTypeCombo = new ComboBox<>();
        gameTypeCombo.getItems().addAll("围棋", "象棋", "五子棋", "国际象棋", "其他");
        gameTypeCombo.setValue("五子棋");

        Spinner<Integer> maxPlayersSpinner = new Spinner<>(2, 10, 4);

        grid.add(new Label("房间名称:"), 0, 0);
        grid.add(roomNameField, 1, 0);
        grid.add(new Label("游戏类型:"), 0, 1);
        grid.add(gameTypeCombo, 1, 1);
        grid.add(new Label("最大玩家数:"), 0, 2);
        grid.add(maxPlayersSpinner, 1, 2);

        dialog.getDialogPane().setContent(grid);

        // 请求焦点
        Platform.runLater(roomNameField::requestFocus);

        // 转换结果
        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == createButtonType) {
                Map<String, Object> result = new HashMap<>();
                result.put("roomName", roomNameField.getText());
                result.put("gameType", gameTypeCombo.getValue());
                result.put("maxPlayers", maxPlayersSpinner.getValue());
                return result;
            }
            return null;
        });

        Optional<Map<String, Object>> result = dialog.showAndWait();
        result.ifPresent(roomData -> {
            String roomName = (String) roomData.get("roomName");
            if (roomName == null || roomName.trim().isEmpty()) {
                AlertUtil.showWarning("输入错误", "房间名称不能为空");
                return;
            }

            String gameType = (String) roomData.get("gameType");
            int maxPlayers = (Integer) roomData.get("maxPlayers");

            // 通过WebSocket创建房间
            webSocketService.createRoom(roomName, gameType, maxPlayers);
            new Thread(() -> {
                try {
                    Thread.sleep(1000); // 等待1秒
                    Room userRoom = apiService.getUserRoom();
                    if (userRoom != null) {
                        Platform.runLater(() -> {
                            // 显示房间界面
                            Stage primaryStage = (Stage) getScene().getWindow();
                            primaryStage.setTitle("房间: " + userRoom.getName() + " - " + currentUser.getUsername());
                            primaryStage.getScene().setRoot(new RoomView(userRoom, currentUser, apiService, webSocketService, appConfig, primaryStage));
                        });
                    }
                } catch (Exception e) {
                    Platform.runLater(() -> AlertUtil.showError("创建房间失败", "无法获取新创建的房间信息"));
                }
            }).start();
        });
    }

    // 添加加入房间的方法
    private void joinRoom(Long roomId) {
        new Thread(() -> {
            try {
                Room room = apiService.joinRoom(roomId);
                Platform.runLater(() -> {
                    // 显示房间界面
                    Stage primaryStage = (Stage) getScene().getWindow();
                    primaryStage.setTitle("房间: " + room.getName() + " - " + currentUser.getUsername());
                    primaryStage.getScene().setRoot(new RoomView(room, currentUser, apiService, webSocketService, appConfig, primaryStage));
                });
            } catch (Exception e) {
                Platform.runLater(() -> AlertUtil.showError("加入房间失败", e.getMessage()));
            }
        }).start();
    }


    // 添加刷新用户信息的方法
    private void refreshUserInfo(GridPane profileGrid, Label usernameLabel, Label clientAddressLabel, Label virtualIpLabel) {
        new Thread(() -> {
            try {
                User updatedUser = apiService.getCurrentUser();
                if (updatedUser != null) {
                    Platform.runLater(() -> {
                        // 确保保留用户名
                        usernameLabel.setText(updatedUser.getUsername());

                        // 更新客户端地址
                        clientAddressLabel.setText(updatedUser.getClientAddress() != null ?
                                updatedUser.getClientAddress() : "未知");

                        // 更新虚拟IP
                        virtualIpLabel.setText(updatedUser.getVirtualIp() != null ?
                                updatedUser.getVirtualIp() : "未分配");

                        // 更新当前用户对象
                        currentUser = updatedUser;

                        statusLabel.setText("个人信息已更新");
                    });
                }
            } catch (Exception e) {
                Platform.runLater(() ->
                        statusLabel.setText("无法获取用户信息: " + e.getMessage())
                );
            }
        }).start();
    }

    private HBox createStatusBar() {
        HBox statusBar = new HBox(10);
        statusBar.setPadding(new Insets(5, 10, 5, 10));
        statusBar.setStyle("-fx-background-color: #f0f0f0; -fx-border-color: #c0c0c0; -fx-border-width: 1 0 0 0;");

        statusLabel = new Label("已连接到服务器");

        Pane spacer = new Pane();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Label versionLabel = new Label("版本: 1.0.0");

        statusBar.getChildren().addAll(statusLabel, spacer, versionLabel);
        return statusBar;
    }

    public void connectWebSocket() {
        webSocketService.setUserStatusUpdateHandler(user -> {
            // 处理用户状态更新
            boolean found = false;
            for (int i = 0; i < activeUsers.size(); i++) {
                User existingUser = activeUsers.get(i);
                if (existingUser.getUsername().equals(user.getUsername())) {
                    existingUser.setActive(user.isActive());
                    activeUsers.set(i, existingUser); // 触发UI更新
                    found = true;
                    break;
                }
            }

            if (!found && user.isActive()) {
                // 添加新用户
                activeUsers.add(user);
            }

            userListView.refresh();
        });

        // 在单独线程中连接WebSocket，避免阻塞UI线程
        new Thread(() -> {
            try {
                webSocketService.connect();
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("连接服务器失败: " + e.getMessage());
                    AlertUtil.showError("连接失败", "无法连接到WebSocket服务器: " + e.getMessage());
                });
            }
        }).start();
    }

    private void refreshUserList() {
        new Thread(() -> {
            try {
                List<User> users = apiService.getAllActiveUsers();
                Platform.runLater(() -> {
                    activeUsers.clear();
                    activeUsers.addAll(users);
                    statusLabel.setText("用户列表已更新 - 在线用户: " + users.size());
                });
            } catch (Exception e) {
                Platform.runLater(() ->
                        statusLabel.setText("无法获取用户列表: " + e.getMessage())
                );
            }
        }).start();
    }

    private void startRefreshTimer() {
        refreshUserList(); // 初始加载

        refreshTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                refreshUserList();
            }
        }, 30000, 30000); // 每30秒刷新一次
    }

    private void handleLogout() {
        if (AlertUtil.showConfirmation("退出登录", "确定要退出登录吗？")) {
            new Thread(() -> {
                try {
                    webSocketService.disconnect();
                    refreshTimer.cancel();
                    apiService.logout();

                    Platform.runLater(() -> {
                        Stage primaryStage = Main.getPrimaryStage();
                        primaryStage.setTitle("游戏大厅 - 登录");
                        primaryStage.setScene(new Scene(new LoginView(), 400, 300));
                        primaryStage.setResizable(false);
                        primaryStage.centerOnScreen();
                    });
                } catch (Exception e) {
                    Platform.runLater(() ->
                            AlertUtil.showError("退出失败", e.getMessage())
                    );
                }
            }).start();
        }
    }

    private void showSettingsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("服务器设置");
        dialog.setHeaderText("配置服务器连接参数");

        ButtonType saveButtonType = new ButtonType("保存", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 150, 10, 10));

        TextField serverUrlField = new TextField(appConfig.getServerUrl());
        TextField wsUrlField = new TextField(appConfig.getWebSocketUrl());

        grid.add(new Label("服务器URL:"), 0, 0);
        grid.add(serverUrlField, 1, 0);
        grid.add(new Label("WebSocket URL:"), 0, 1);
        grid.add(wsUrlField, 1, 1);

        dialog.getDialogPane().setContent(grid);

        dialog.setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                return ButtonType.OK;
            }
            return ButtonType.CANCEL;
        });

        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                if (AlertUtil.showConfirmation("重新连接", "设置已更改，需要重新连接。确定继续吗？")) {
                    appConfig.setServerUrl(serverUrlField.getText());
                    appConfig.setWebSocketUrl(wsUrlField.getText());
                    appConfig.saveConfig();

                    // 重新登录
                    handleLogout();
                }
            }
        });
    }

    private void showAboutDialog() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于");
        alert.setHeaderText("游戏大厅");
        alert.setContentText("基于N2N的虚拟局域网联机平台\n作者: 文日\n\n感谢您的使用！");
        alert.showAndWait();
    }

    public void disconnect() {
        // 停止刷新定时器
        if (refreshTimer != null) {
            refreshTimer.cancel();
        }

        // 关闭WebSocket连接
        if (webSocketService != null) {
            try {
                webSocketService.disconnect();
            } catch (Exception e) {
                System.err.println("关闭WebSocket连接失败: " + e.getMessage());
            }
        }

        System.out.println("已断开与服务器的连接");
    }
}