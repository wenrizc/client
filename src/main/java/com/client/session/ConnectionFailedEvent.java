package com.client.session;

import org.springframework.context.ApplicationEvent;

/**
 * 连接失败事件
 */
public class ConnectionFailedEvent extends ApplicationEvent {
    private final String message;

    public ConnectionFailedEvent(Object source, String message) {
        super(source);
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}