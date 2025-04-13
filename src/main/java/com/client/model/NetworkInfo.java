package com.client.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class NetworkInfo {
    private String virtualIp;
    private String networkId;
    private String networkName;
    private String networkSecret;
    private String networkType;
    private String superNodeIp;
    private int superNodePort;

    public NetworkInfo() {
        // 默认构造函数
    }

    public String getVirtualIp() {
        return virtualIp;
    }

    public void setVirtualIp(String virtualIp) {
        this.virtualIp = virtualIp;
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

    public String getNetworkSecret() {
        return networkSecret;
    }

    public void setNetworkSecret(String networkSecret) {
        this.networkSecret = networkSecret;
    }

    public String getNetworkType() {
        return networkType;
    }

    public void setNetworkType(String networkType) {
        this.networkType = networkType;
    }

    public String getSuperNodeIp() {
        return superNodeIp;
    }

    public void setSuperNodeIp(String superNodeIp) {
        this.superNodeIp = superNodeIp;
    }

    public int getSuperNodePort() {
        return superNodePort;
    }

    public void setSuperNodePort(int superNodePort) {
        this.superNodePort = superNodePort;
    }

    @Override
    public String toString() {
        return "NetworkInfo{" +
                "virtualIp='" + virtualIp + '\'' +
                ", networkId='" + networkId + '\'' +
                ", networkName='" + networkName + '\'' +
                ", networkType='" + networkType + '\'' +
                ", superNodeIp='" + superNodeIp + '\'' +
                ", superNodePort=" + superNodePort +
                '}';
    }
}