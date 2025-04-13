package com.client.session;

import com.client.model.User;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

@Component
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    private static final String PREF_SESSION_ID = "sessionId";
    private final StringProperty sessionId = new SimpleStringProperty(null);
    private User currentUser;
    private final Preferences prefs = Preferences.userNodeForPackage(SessionManager.class);
    private final List<Consumer<String>> sessionChangeListeners = new ArrayList<>();

    public SessionManager() {
        // 尝试从持久化存储加载会话
        String savedSessionId = prefs.get(PREF_SESSION_ID, null);
        sessionId.set(savedSessionId);
        logger.debug("初始化SessionManager, 已恢复会话ID: {}", savedSessionId != null);
    }

    public void setSessionId(String newSessionId) {
        this.sessionId.set(newSessionId);
        saveSessionId();

        // 兼容旧代码，同时触发监听器
        for (Consumer<String> listener : sessionChangeListeners) {
            listener.accept(newSessionId);
        }
    }

    public String getSessionId() {
        return sessionId.get();
    }

    public StringProperty sessionIdProperty() {
        return sessionId;
    }


    public User getCurrentUser() {
        return currentUser;
    }

    public void setCurrentUser(User user) {
        this.currentUser = user;
    }

    public boolean hasValidSession() {
        return getSessionId() != null && currentUser != null;
    }

    public void invalidateSession() {
        logger.info("会话已注销");
        sessionId.set(null);
        currentUser = null;
        prefs.remove(PREF_SESSION_ID);
    }

    public void saveSessionId() {
        String id = sessionId.get();
        if (id != null) {
            prefs.put(PREF_SESSION_ID, id);
            logger.debug("会话ID已保存");
        }
    }

    public boolean loadSavedSession() {
        String id = prefs.get(PREF_SESSION_ID, null);
        sessionId.set(id);
        return id != null;
    }
}