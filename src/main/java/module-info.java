module com.client {
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.web;
    requires java.net.http;
    requires com.fasterxml.jackson.databind;
    requires spring.websocket;
    requires spring.messaging;
    requires java.sql;

    exports com.client;
    exports com.client.model;
    exports com.client.ui;
    exports com.client.service;
    exports com.client.util;
    exports com.client.config;
}