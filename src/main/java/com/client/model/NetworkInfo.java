package com.client.model;

/**
 * 网络信息模型
 */
public class NetworkInfo {
    private String username;
    private String virtualIp;
    private boolean inRoom;
    private Long roomId;
    private String roomName;
    private String networkId;
    private String networkName;
    private String networkType;
    private String networkSecret;
    private String supernode;

    public NetworkInfo() {
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getVirtualIp() {
        return virtualIp;
    }

    public void setVirtualIp(String virtualIp) {
        this.virtualIp = virtualIp;
    }

    public boolean isInRoom() {
        return inRoom;
    }

    public void setInRoom(boolean inRoom) {
        this.inRoom = inRoom;
    }

    public Long getRoomId() {
        return roomId;
    }

    public void setRoomId(Long roomId) {
        this.roomId = roomId;
    }

    public String getRoomName() {
        return roomName;
    }

    public void setRoomName(String roomName) {
        this.roomName = roomName;
    }

    public String getNetworkId() {
        return networkId;
    }

    public void setNetworkId(String networkId) {
        this.networkId = networkId;
    }

    public String getNetworkName() {
        return networkName;
    }

    public void setNetworkName(String networkName) {
        this.networkName = networkName;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    // 新增的getter和setter
    public String getNetworkSecret() {
        return networkSecret;
    }

    public void setNetworkSecret(String networkSecret) {
        this.networkSecret = networkSecret;
    }

    public String getSupernode() {
        return supernode;
    }

    public void setSupernode(String supernode) {
        this.supernode = supernode;
    }

    @Override
    public String toString() {
        return "NetworkInfo{" +
                "username='" + username + '\'' +
                ", virtualIp='" + virtualIp + '\'' +
                ", inRoom=" + inRoom +
                ", roomId=" + roomId +
                ", roomName='" + roomName + '\'' +
                ", networkId='" + networkId + '\'' +
                ", networkName='" + networkName + '\'' +
                ", networkType='" + networkType + '\'' +
                ", supernode='" + supernode + '\'' +
                '}';
    }
}