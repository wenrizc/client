package com.client.view;

public enum FxmlView {
    LOGIN("login.fxml"),
    LOBBY("lobby.fxml"),
    ROOM("room.fxml"),
    ROOM_LIST("room_list.fxml");

    private final String fxmlFile;

    FxmlView(String fxmlFile) {
        this.fxmlFile = fxmlFile;
    }

    public String getFxmlFile() {
        return fxmlFile;
    }
}