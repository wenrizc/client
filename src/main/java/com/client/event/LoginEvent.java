package com.client.event;

import com.client.model.User;
import org.springframework.context.ApplicationEvent;

/**
 * 登录事件
 */
public class LoginEvent extends ApplicationEvent {
    private final User user;

    public LoginEvent(Object source, User user) {
        super(source);
        this.user = user;
    }

    public User getUser() {
        return user;
    }
}