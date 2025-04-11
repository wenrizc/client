package com.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@JsonIgnoreProperties(ignoreUnknown = true) // 忽略未知属性
public class Room {
    private Long id;
    private String name;
    private String gameType;
    private int maxPlayers;
    private Instant creationTime;
    private String creatorUsername;
    private String status;
    private String n2nNetworkId;
    private String n2nNetworkName;
    private String n2nNetworkSecret;
    private Set<String> players = new HashSet<>();

    public Room() {
    }

    // Getters 和 setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGameType() {
        return gameType;
    }

    public void setGameType(String gameType) {
        this.gameType = gameType;
    }

    public int getMaxPlayers() {
        return maxPlayers;
    }

    public void setMaxPlayers(int maxPlayers) {
        this.maxPlayers = maxPlayers;
    }

    public Instant getCreationTime() {
        return creationTime;
    }

    public void setCreationTime(Instant creationTime) {
        this.creationTime = creationTime;
    }

    public String getCreatorUsername() {
        return creatorUsername;
    }

    public void setCreatorUsername(String creatorUsername) {
        this.creatorUsername = creatorUsername;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getN2nNetworkId() {
        return n2nNetworkId;
    }

    public void setN2nNetworkId(String n2nNetworkId) {
        this.n2nNetworkId = n2nNetworkId;
    }

    public String getN2nNetworkName() {
        return n2nNetworkName;
    }

    public void setN2nNetworkName(String n2nNetworkName) {
        this.n2nNetworkName = n2nNetworkName;
    }

    public String getN2nNetworkSecret() {
        return n2nNetworkSecret;
    }

    public void setN2nNetworkSecret(String n2nNetworkSecret) {
        this.n2nNetworkSecret = n2nNetworkSecret;
    }

    public Set<String> getPlayers() {
        return players;
    }

    public void setPlayers(Set<String> players) {
        this.players = players;
    }

    public boolean isFull() {
        return players.size() >= maxPlayers;
    }

    public boolean isCreator(String username) {
        return creatorUsername != null && creatorUsername.equals(username);
    }

    @Override
    public String toString() {
        return name + " [" + players.size() + "/" + maxPlayers + "] - " + gameType;
    }
}