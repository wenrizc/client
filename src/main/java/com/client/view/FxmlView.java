package com.client.view;

public enum FxmlView {
    LOGIN("login.fxml", "用户登录"),
    LOBBY("lobby.fxml", "游戏大厅"),
    ROOM("room.fxml", "游戏房间"),
    SERVER_SETTINGS("server_settings.fxml", "服务器设置"),
    ROOM_LIST("room_list.fxml", "房间列表"),
    CREATE_ROOM("create_room.fxml", "创建房间"),
    N2N_STATUS("n2n_status.fxml", "N2N状态");

    private final String fxmlFile;
    private final String title;

    FxmlView(String fxmlFile, String title) {
        this.fxmlFile = fxmlFile;
        this.title = title;
    }

    public String getFxmlFile() {
        return fxmlFile;
    }

    public String getTitle() {
        return title;
    }
}