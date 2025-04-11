package com.client.model;

import java.time.Instant;

public class User {
    private Long id;
    private String username;
    private String sessionId;
    private String clientAddress;
    private String virtualIp;
    private boolean active;
    private Instant lastActiveTime;

    public User() {}

    public User(Long id, String username) {
        this.id = id;
        this.username = username;
        this.active = true;
        this.lastActiveTime = Instant.now();
    }

    // Getters and setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getClientAddress() {
        return clientAddress;
    }

    public void setClientAddress(String clientAddress) {
        this.clientAddress = clientAddress;
    }

    public String getVirtualIp() {
        return virtualIp;
    }

    public void setVirtualIp(String virtualIp) {
        this.virtualIp = virtualIp;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Instant getLastActiveTime() {
        return lastActiveTime;
    }

    public void setLastActiveTime(Instant lastActiveTime) {
        this.lastActiveTime = lastActiveTime;
    }

    @Override
    public String toString() {
        return username + (active ? " (在线)" : " (离线)");
    }
}