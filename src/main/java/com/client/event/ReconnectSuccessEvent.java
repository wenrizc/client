package com.client.event;

import org.springframework.context.ApplicationEvent;

/**
 * 重连成功事件
 */
public class ReconnectSuccessEvent extends ApplicationEvent {
    public ReconnectSuccessEvent(Object source) {
        super(source);
    }
}