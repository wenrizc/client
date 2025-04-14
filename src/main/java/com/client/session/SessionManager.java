package com.client.session;

import com.client.model.User;
import javafx.application.Platform;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/**
 * 会话管理器，负责维护用户会话状态
 */
@Component
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static final String PREF_SESSION_ID = "sessionId";
    private static final String PREF_LAST_LOGIN = "lastLogin";
    private static final long SESSION_TIMEOUT = 30 * 60 * 1000; // 30分钟会话超时
    private final StringProperty sessionId = new SimpleStringProperty(null);
    private final List<Consumer<String>> sessionChangeListeners = new ArrayList<>();
    private final Preferences prefs = Preferences.userNodeForPackage(SessionManager.class);
    private final ApplicationEventPublisher eventPublisher;
    private Timer sessionTimeoutTimer;

    private User currentUser;
    private long lastLoginTime;


    public SessionManager(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;

        // 尝试从持久化存储加载会话
        String savedSessionId = prefs.get(PREF_SESSION_ID, null);
        sessionId.set(savedSessionId);
        lastLoginTime = prefs.getLong(PREF_LAST_LOGIN, 0);

        logger.debug("初始化SessionManager, 已恢复会话ID: {}", savedSessionId != null);
    }

    /**
     * 取消会话超时定时器
     */
    private void cancelSessionTimeout() {
        if (sessionTimeoutTimer != null) {
            sessionTimeoutTimer.cancel();
            sessionTimeoutTimer = null;
        }
    }

    /**
     * 清除本地会话
     */
    private void clearSession() {
        setSessionId(null);
        currentUser = null;
        prefs.remove(PREF_SESSION_ID);
    }

    /**
     * 获取会话ID
     */
    public String getSessionId() {
        return sessionId.get();
    }

    /**
     * 设置会话ID
     */
    public void setSessionId(String newSessionId) {
        boolean wasLoggedIn = hasValidSession();
        this.sessionId.set(newSessionId);

        if (newSessionId != null) {
            lastLoginTime = System.currentTimeMillis();
            prefs.putLong(PREF_LAST_LOGIN, lastLoginTime);
            saveSessionId();
        }
        // 通知监听器
        for (Consumer<String> listener : sessionChangeListeners) {
            listener.accept(newSessionId);
        }

        // 发布登录状态变更事件
        boolean isLoggedIn = hasValidSession();
        if (wasLoggedIn != isLoggedIn) {
            Platform.runLater(() -> {
                if (isLoggedIn) {
                    eventPublisher.publishEvent(new LoginEvent(this, currentUser));
                } else {
                    eventPublisher.publishEvent(new LogoutEvent(this));
                }
            });
        }
    }

    /**
     * 获取会话ID属性
     */
    public StringProperty sessionIdProperty() {
        return sessionId;
    }

    /**
     * 添加会话变更监听器
     */
    public void addSessionChangeListener(Consumer<String> listener) {
        sessionChangeListeners.add(listener);
    }

    /**
     * 移除会话变更监听器
     */
    public void removeSessionChangeListener(Consumer<String> listener) {
        sessionChangeListeners.remove(listener);
    }

    /**
     * 获取当前用户
     */
    public User getCurrentUser() {
        return currentUser;
    }

    /**
     * 设置当前用户
     */
    public void setCurrentUser(User user) {
        boolean wasLoggedIn = this.currentUser != null;
        this.currentUser = user;

        // 发布用户信息更新事件
        if (user != null && wasLoggedIn) {
            Platform.runLater(() -> eventPublisher.publishEvent(new UserInfoUpdateEvent(this, user)));
        }
    }

    /**
     * 检查是否有有效会话
     */
    public boolean hasValidSession() {
        return getSessionId() != null && currentUser != null;
    }

    /**
     * 创建会话有效性绑定
     */
    public BooleanBinding hasValidSessionBinding() {
        return sessionId.isNotNull();
    }

    /**
     * 使会话无效（登出）
     */
    public void invalidateSession() {
        User oldUser = currentUser;
        sessionId.set(null);
        currentUser = null;
        prefs.remove(PREF_SESSION_ID);

        logger.info("会话已注销");

        // 通知监听器
        for (Consumer<String> listener : sessionChangeListeners) {
            listener.accept(null);
        }

        // 发布登出事件
        if (oldUser != null) {
            Platform.runLater(() -> eventPublisher.publishEvent(new LogoutEvent(this)));
        }
    }

    /**
     * 保存会话ID到持久存储
     */
    private void saveSessionId() {
        String id = sessionId.get();
        if (id != null) {
            prefs.put(PREF_SESSION_ID, id);
            logger.debug("会话ID已保存");
        } else {
            prefs.remove(PREF_SESSION_ID);
        }
    }

    /**
     * 加载已保存的会话
     */
    public boolean loadSavedSession() {
        String id = prefs.get(PREF_SESSION_ID, null);
        sessionId.set(id);
        return id != null;
    }

    /**
     * 获取上次登录时间
     */
    public long getLastLoginTime() {
        return lastLoginTime;
    }

    /**
     * 检查会话是否过期
     */
    public boolean isSessionExpired() {
        // 本地简单判断，超过24小时认为过期
        return lastLoginTime > 0 &&
                System.currentTimeMillis() - lastLoginTime > 24 * 60 * 60 * 1000;
    }
}