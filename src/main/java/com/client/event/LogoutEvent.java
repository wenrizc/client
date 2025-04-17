package com.client.event;

import org.springframework.context.ApplicationEvent;

/**
 * 登出事件
 */
public class LogoutEvent extends ApplicationEvent {
    public LogoutEvent(Object source) {
        super(source);
    }
}