module com.client {
    // JavaFX依赖
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.graphics;
    requires javafx.web;
    requires org.controlsfx.controls;
    requires com.jfoenix;

    // Spring框架依赖
    requires spring.boot;
    requires spring.boot.autoconfigure;
    requires spring.context;
    requires spring.beans;
    requires spring.core;
    requires spring.web;
    requires spring.webflux;
    requires spring.websocket;
    requires spring.messaging;

    // 其他依赖
    requires com.fasterxml.jackson.databind;
    requires com.fasterxml.jackson.datatype.jsr310;
    requires java.net.http;
    requires org.slf4j;
    requires java.sql;
    requires reactor.core;
    requires jakarta.annotation;
    requires java.prefs;
    requires de.jensd.fx.glyphs.commons;
    requires de.jensd.fx.glyphs.materialicons;


    // 向Spring框架开放所有需要的包
    opens com.client to javafx.fxml, spring.beans, spring.context, spring.core;
    opens com.client.controller to javafx.fxml, spring.beans, spring.context, spring.core;
    opens com.client.config to spring.beans, spring.context, spring.core;
    opens com.client.service to spring.beans, spring.context, spring.core;
    opens com.client.event to spring.beans, spring.context, spring.core;
    opens com.client.util to spring.beans, spring.context, spring.core;
    opens com.client.model to spring.beans, spring.context, spring.core, com.fasterxml.jackson.databind;
    opens com.client.exception to spring.beans, spring.context, spring.core;

    // 资源文件夹开放
    opens fxml;
    opens styles;

    // 导出公共API包
    exports com.client;
    exports com.client.controller;
    exports com.client.config;
    exports com.client.service;
    exports com.client.util;
    exports com.client.model;
    exports com.client.exception;
    exports com.client.enums;
    opens com.client.enums to spring.beans, spring.context, spring.core;
    exports com.client.event;
    exports com.client.util.n2n;
    opens com.client.util.n2n to spring.beans, spring.context, spring.core;
}