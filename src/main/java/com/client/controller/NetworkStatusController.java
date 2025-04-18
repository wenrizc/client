package com.client.controller;

import com.client.service.NetworkStatusService;
import com.client.service.VirtualNetworkService;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 网络状态显示组件
 * <p>
 * 显示服务器和虚拟网络连接状态
 * </p>
 */
@Component
public class NetworkStatusController {

    @FXML
    private Circle serverStatusIndicator;

    @FXML
    private Circle n2nStatusIndicator;

    @FXML
    private Label serverStatusLabel;

    @FXML
    private Label n2nStatusLabel;

    @FXML
    private Button reconnectButton;

    private final NetworkStatusService networkStatusService;
    private final VirtualNetworkService virtualNetworkService;

    @Autowired
    public NetworkStatusController(NetworkStatusService networkStatusService,
                               VirtualNetworkService virtualNetworkService) {
        this.networkStatusService = networkStatusService;
        this.virtualNetworkService = virtualNetworkService;
    }

    @FXML
    public void initialize() {
        // 绑定服务器连接状态
        networkStatusService.connectedProperty().addListener((obs, oldVal, newVal) -> {
            serverStatusIndicator.setFill(newVal ? Color.GREEN : Color.RED);
        });

        networkStatusService.statusMessageProperty().addListener((obs, oldVal, newVal) -> {
            serverStatusLabel.setText(newVal);
        });

        // 初始设置服务器状态
        serverStatusIndicator.setFill(networkStatusService.isConnected() ? Color.GREEN : Color.RED);
        serverStatusLabel.setText(networkStatusService.getStatusMessage());

        // 绑定虚拟网络连接状态
        networkStatusService.n2nConnectedProperty().addListener((obs, oldVal, newVal) -> {
            n2nStatusIndicator.setFill(newVal ? Color.GREEN : Color.RED);
        });

        networkStatusService.n2nStatusMessageProperty().addListener((obs, oldVal, newVal) -> {
            n2nStatusLabel.setText(newVal);
        });

        // 初始设置虚拟网络状态
        n2nStatusIndicator.setFill(networkStatusService.isN2nConnected() ? Color.GREEN : Color.RED);
        n2nStatusLabel.setText(networkStatusService.getN2nStatusMessage());

        // 设置重连按钮事件
        reconnectButton.setOnAction(event -> {
            reconnectButton.setDisable(true);
            reconnectButton.setText("连接中...");

            virtualNetworkService.refreshNetworkConnection()
                    .thenAccept(success -> {
                        javafx.application.Platform.runLater(() -> {
                            reconnectButton.setDisable(false);
                            reconnectButton.setText("重新连接");
                        });
                    });
        });
    }
}
