package com.cse471.p2p.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Peer bilgilerini tutar
 * Subnet-aware P2P network için genişletilmiş
 */
public class PeerInfo {
    private String peerId;
    private String peerName;
    private String ipAddress;
    private int udpPort;
    private int tcpPort;
    private long lastSeen;
    
    // Subnet bilgileri
    private String subnetId;  // Örn: "Subnet-A", "Subnet-B"
    private boolean isGatewayPeer;  // Bu peer gateway mi? (subnet'ler arası bağlantı için)
    private List<String> connectedSubnets;  // Bağlı olduğu subnet'ler (gateway için)
    
    public PeerInfo() {
        this.connectedSubnets = new ArrayList<>();
    }
    
    public PeerInfo(String peerId, String peerName, String ipAddress, int udpPort, int tcpPort) {
        this.peerId = peerId;
        this.peerName = peerName;
        this.ipAddress = ipAddress;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.lastSeen = System.currentTimeMillis();
        this.connectedSubnets = new ArrayList<>();
        this.isGatewayPeer = false;
    }
    
    public PeerInfo(String peerId, String peerName, String ipAddress, int udpPort, int tcpPort, String subnetId) {
        this.peerId = peerId;
        this.peerName = peerName;
        this.ipAddress = ipAddress;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.subnetId = subnetId;
        this.lastSeen = System.currentTimeMillis();
        this.connectedSubnets = new ArrayList<>();
        this.isGatewayPeer = false;
    }
    
    public String getPeerId() {
        return peerId;
    }
    
    public void setPeerId(String peerId) {
        this.peerId = peerId;
    }
    
    public String getPeerName() {
        return peerName;
    }
    
    public void setPeerName(String peerName) {
        this.peerName = peerName;
    }
    
    public String getIpAddress() {
        return ipAddress;
    }
    
    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }
    
    public int getUdpPort() {
        return udpPort;
    }
    
    public void setUdpPort(int udpPort) {
        this.udpPort = udpPort;
    }
    
    public int getTcpPort() {
        return tcpPort;
    }
    
    public void setTcpPort(int tcpPort) {
        this.tcpPort = tcpPort;
    }
    
    public long getLastSeen() {
        return lastSeen;
    }
    
    public void setLastSeen(long lastSeen) {
        this.lastSeen = lastSeen;
    }
    
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
    
    public String getAddress() {
        return ipAddress + ":" + tcpPort;
    }
    
    // Subnet getters and setters
    public String getSubnetId() {
        return subnetId;
    }
    
    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }
    
    public boolean isGatewayPeer() {
        return isGatewayPeer;
    }
    
    public void setGatewayPeer(boolean gatewayPeer) {
        isGatewayPeer = gatewayPeer;
    }
    
    public List<String> getConnectedSubnets() {
        return new ArrayList<>(connectedSubnets);
    }
    
    public void setConnectedSubnets(List<String> connectedSubnets) {
        this.connectedSubnets = connectedSubnets != null ? new ArrayList<>(connectedSubnets) : new ArrayList<>();
    }
    
    public void addConnectedSubnet(String subnetId) {
        if (subnetId != null && !connectedSubnets.contains(subnetId)) {
            connectedSubnets.add(subnetId);
        }
    }
    
    /**
     * Bu peer başka bir peer ile aynı subnet'te mi?
     */
    public boolean isInSameSubnet(PeerInfo other) {
        if (this.subnetId == null || other == null || other.getSubnetId() == null) {
            return false;
        }
        return this.subnetId.equals(other.getSubnetId());
    }
    
    /**
     * Bu peer belirtilen subnet'e bağlı mı? (gateway için)
     */
    public boolean isConnectedToSubnet(String subnetId) {
        return connectedSubnets.contains(subnetId);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerInfo peerInfo = (PeerInfo) o;
        return Objects.equals(peerId, peerInfo.peerId);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(peerId);
    }
    
    @Override
    public String toString() {
        String subnetInfo = subnetId != null ? ", subnet=" + subnetId : "";
        String gatewayInfo = isGatewayPeer ? ", gateway" : "";
        return "PeerInfo{" +
                "peerId='" + peerId + '\'' +
                ", peerName='" + peerName + '\'' +
                ", address=" + getAddress() +
                subnetInfo +
                gatewayInfo +
                '}';
    }
}

