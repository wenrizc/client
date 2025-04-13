package com.client.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AppProperties {

    @Value("${server.url}")
    private String serverUrl;

    @Value("${ws.server.url}")
    private String wsServerUrl;

    @Value("${app.resource.fxml}")
    private String fxmlPath;

    @Value("${app.resource.style}")
    private String stylePath;

    @Value("${app.resource.image}")
    private String imagePath;

    @Value("${app.stage.title}")
    private String stageTitle;

    @Value("${app.stage.width}")
    private double stageWidth;

    @Value("${app.stage.height}")
    private double stageHeight;

    @Value("${app.stage.resizable}")
    private boolean stageResizable;

    public String getServerUrl() {
        return serverUrl;
    }

    public String getWsServerUrl() {
        return wsServerUrl;
    }

    public String getFxmlPath() {
        return fxmlPath;
    }

    public String getStylePath() {
        return stylePath;
    }

    public String getImagePath() {
        return imagePath;
    }

    public String getStageTitle() {
        return stageTitle;
    }

    public double getStageWidth() {
        return stageWidth;
    }

    public double getStageHeight() {
        return stageHeight;
    }

    public boolean isStageResizable() {
        return stageResizable;
    }
}