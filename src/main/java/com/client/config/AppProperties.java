package com.client.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

@Component
public class AppProperties {

    private static final Logger logger = LoggerFactory.getLogger(AppProperties.class);
    private static final String CONFIG_FILE = "application.properties";

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

    @Value("${app.version:1.0.0}")
    private String version;

    public String getServerUrl() {
        return serverUrl;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
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

    public String getVersion() {
        return version;
    }


    // 保存配置到文件
    public void save() {
        Properties props = new Properties();

        // 先加载现有配置
        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                logger.error("无法读取配置文件", e);
            }
        }

        // 更新属性
        props.setProperty("server.url", serverUrl);

        // 保存回文件
        try (FileOutputStream out = new FileOutputStream(configFile)) {
            props.store(out, "Game Hall Client Configuration");
            logger.info("配置已保存到 {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("无法保存配置文件", e);
        }
    }
}