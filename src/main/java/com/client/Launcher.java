package com.client;

/**
 * JavaFX应用程序启动器
 * 用于解决JavaFX模块化问题
 */
public class Launcher {
    public static void main(String[] args) {
        System.setProperty("jdk.module.illegal.access.warns", "false");
        ClientApplication.main(args);
    }
}