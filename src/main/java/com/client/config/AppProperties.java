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
    public static final String DEFAULT_SERVER_URL = "http://localhost:8080";

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

    @Value("${app.n2n.path:}")
    private String n2nPath = "";

    @Value("${n2n.autoReconnect:true}")
    private boolean n2nAutoReconnect;

    @Value("${n2n.reconnectInterval:30}")
    private int n2nReconnectInterval;

    @Value("${n2n.logLevel:INFO}")
    private String n2nLogLevel;

    @Value("${n2n.mtu:1400}")
    private int n2nMtu;

    @Value("${n2n.debugMode:false}")
    private boolean n2nDebugMode;

    public String getN2nPath() {
        return n2nPath;
    }

    public void setN2nPath(String n2nPath) {
        this.n2nPath = n2nPath;
    }

    public boolean isN2nAutoReconnect() {
        return n2nAutoReconnect;
    }

    public void setN2nAutoReconnect(boolean n2nAutoReconnect) {
        this.n2nAutoReconnect = n2nAutoReconnect;
    }

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

    public int getN2nReconnectInterval() {
        return n2nReconnectInterval;
    }

    public void setN2nReconnectInterval(int n2nReconnectInterval) {
        this.n2nReconnectInterval = n2nReconnectInterval;
    }

    public String getN2nLogLevel() {
        return n2nLogLevel;
    }

    public void setN2nLogLevel(String n2nLogLevel) {
        this.n2nLogLevel = n2nLogLevel;
    }

    public int getN2nMtu() {
        return n2nMtu;
    }

    public void setN2nMtu(int n2nMtu) {
        this.n2nMtu = n2nMtu;
    }

    public boolean isN2nDebugMode() {
        return n2nDebugMode;
    }

    public void setN2nDebugMode(boolean n2nDebugMode) {
        this.n2nDebugMode = n2nDebugMode;
    }

    /**
     * 判断当前是否使用默认服务器URL
     * @return 如果使用默认URL则返回true
     */
    public boolean isDefaultServerUrl() {
        return DEFAULT_SERVER_URL.equals(serverUrl);
    }

    /**
     * 重置为默认服务器URL
     */
    public void resetToDefaultServerUrl() {
        serverUrl = DEFAULT_SERVER_URL;
        save();
    }

    public void save() {
        Properties props = new Properties();

        File configFile = new File(CONFIG_FILE);
        if (configFile.exists()) {
            try (FileInputStream in = new FileInputStream(configFile)) {
                props.load(in);
            } catch (IOException e) {
                logger.error("无法读取配置文件", e);
            }
        }

        props.setProperty("server.url", serverUrl);

        try (FileOutputStream out = new FileOutputStream(configFile)) {
            props.store(out, "Game Hall Client Configuration");
            logger.info("配置已保存到 {}", configFile.getAbsolutePath());
        } catch (IOException e) {
            logger.error("无法保存配置文件", e);
        }
    }
}