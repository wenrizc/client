package com.client.session;

import com.client.model.User;
import org.springframework.context.ApplicationEvent;

/**
 * 用户信息更新事件
 */
public class UserInfoUpdateEvent extends ApplicationEvent {
    private final User user;

    public UserInfoUpdateEvent(Object source, User user) {
        super(source);
        this.user = user;
    }

    public User getUser() {
        return user;
    }
}