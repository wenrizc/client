package com.client.controller;

import com.client.service.NetworkStatusService;
import com.client.util.n2n.N2NClientManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

@Component
public class N2NStatusController extends BaseController {
    private static final Logger logger = LoggerFactory.getLogger(N2NStatusController.class);

    @Autowired
    private NetworkStatusService networkStatusService;

    @FXML
    private TextField statusField;

    @FXML
    private TextField networkIdField;

    @FXML
    private TextField uptimeField;

    @FXML
    private TextArea commandField;

    @FXML
    private ListView<String> outputLogList;

    @FXML
    private ListView<String> errorLogList;

    private Timer refreshTimer;

    @FXML
    public void initialize() {
        logger.debug("初始化N2N状态控制器");

        // 初始化视图
        updateStatusDisplay();

        // 设置自动刷新
        startAutoRefresh();
    }

    @FXML
    public void refreshStatus() {
        updateStatusDisplay();
    }

    @FXML
    public void clearLogs() {
        outputLogList.getItems().clear();
        errorLogList.getItems().clear();
    }

    private void updateStatusDisplay() {
        N2NClientManager.N2NStatusInfo status = networkStatusService.getN2nDetailedStatus();

        Platform.runLater(() -> {
            statusField.setText(status.isRunning() ? "运行中" : "已停止");
            networkIdField.setText(status.getNetworkId() != null ? status.getNetworkId() : "无");
            uptimeField.setText(formatUptime(status.getUptime()));
            commandField.setText(status.getLastCommand() != null ? status.getLastCommand() : "");

            // 更新日志列表
            updateLogList(outputLogList, status.getRecentOutput());
            updateLogList(errorLogList, status.getRecentErrors());
        });
    }

    private void updateLogList(ListView<String> listView, List<String> logs) {
        listView.getItems().clear();
        if (logs != null && !logs.isEmpty()) {
            listView.getItems().addAll(logs);
            listView.scrollTo(listView.getItems().size() - 1);
        }
    }

    private String formatUptime(long seconds) {
        if (seconds <= 0) {
            return "0秒";
        }

        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append("小时 ");
        }
        if (minutes > 0 || hours > 0) {
            sb.append(minutes).append("分钟 ");
        }
        sb.append(secs).append("秒");

        return sb.toString();
    }

    private void startAutoRefresh() {
        stopAutoRefresh(); // 确保之前的定时器被停止

        refreshTimer = new Timer(true);
        refreshTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                updateStatusDisplay();
            }
        }, 5000, 5000); // 每5秒刷新一次
    }

    private void stopAutoRefresh() {
        if (refreshTimer != null) {
            refreshTimer.cancel();
            refreshTimer = null;
        }
    }

    public void onClose() {
        stopAutoRefresh();
    }
}