package com.client.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class AppConfig {

    private static final String CONFIG_FILE = "config.properties";
    private Properties properties;

    // 默认配置
    private static final String DEFAULT_SERVER_URL = "http://localhost:8080";
    private static final String DEFAULT_WS_URL = "ws://localhost:8080/ws";

    public AppConfig() {
        properties = new Properties();
        loadConfig();
    }

    private void loadConfig() {
        File configFile = new File(CONFIG_FILE);

        if (configFile.exists()) {
            try (FileInputStream fis = new FileInputStream(configFile)) {
                properties.load(fis);
                System.out.println("配置已加载");
            } catch (IOException e) {
                System.err.println("无法加载配置文件: " + e.getMessage());
                setDefaults();
            }
        } else {
            setDefaults();
            saveConfig();
        }
    }

    private void setDefaults() {
        properties.setProperty("server.url", DEFAULT_SERVER_URL);
        properties.setProperty("websocket.url", DEFAULT_WS_URL);
    }

    public void saveConfig() {
        try (FileOutputStream fos = new FileOutputStream(CONFIG_FILE)) {
            properties.store(fos, "游戏大厅客户端配置");
            System.out.println("配置已保存");
        } catch (IOException e) {
            System.err.println("无法保存配置文件: " + e.getMessage());
        }
    }

    public String getServerUrl() {
        return properties.getProperty("server.url", DEFAULT_SERVER_URL);
    }

    public String getWebSocketUrl() {
        return properties.getProperty("websocket.url", DEFAULT_WS_URL);
    }

    public void setServerUrl(String url) {
        properties.setProperty("server.url", url);
    }

    public void setWebSocketUrl(String url) {
        properties.setProperty("websocket.url", url);
    }
}