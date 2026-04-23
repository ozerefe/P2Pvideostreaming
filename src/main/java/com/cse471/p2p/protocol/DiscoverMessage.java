package com.cse471.p2p.protocol;

/**
 * UDP Discovery mesajı
 * Subnet-aware P2P network için genişletilmiş
 */
public class DiscoverMessage {
    private String type;           // MessageType enum değeri
    private String msgId;          // UUID - döngü kontrolü için
    private String senderId;       // Gönderen peer ID
    private String senderName;     // Gönderen peer name
    private String senderIp;       // Gönderen IP
    private int senderUdpPort;     // Gönderen UDP port
    private int senderTcpPort;     // Gönderen TCP port
    private int ttl;               // Time-to-live / hop count
    private long timestamp;        // Unix timestamp
    
    // Subnet bilgileri
    private String subnetId;       // Gönderen peer'ın subnet'i
    private boolean isGatewayPeer; // Gönderen peer gateway mi?
    
    public DiscoverMessage() {
    }
    
    public DiscoverMessage(String type, String msgId, String senderId, String senderName,
                          String senderIp, int senderUdpPort, int senderTcpPort, int ttl) {
        this.type = type;
        this.msgId = msgId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.senderIp = senderIp;
        this.senderUdpPort = senderUdpPort;
        this.senderTcpPort = senderTcpPort;
        this.ttl = ttl;
        this.timestamp = System.currentTimeMillis();
        this.isGatewayPeer = false;
    }
    
    public DiscoverMessage(String type, String msgId, String senderId, String senderName,
                          String senderIp, int senderUdpPort, int senderTcpPort, int ttl,
                          String subnetId, boolean isGatewayPeer) {
        this.type = type;
        this.msgId = msgId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.senderIp = senderIp;
        this.senderUdpPort = senderUdpPort;
        this.senderTcpPort = senderTcpPort;
        this.ttl = ttl;
        this.timestamp = System.currentTimeMillis();
        this.subnetId = subnetId;
        this.isGatewayPeer = isGatewayPeer;
    }
    
    // Getters and setters
    public String getType() {
        return type;
    }
    
    public void setType(String type) {
        this.type = type;
    }
    
    public String getMsgId() {
        return msgId;
    }
    
    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }
    
    public String getSenderId() {
        return senderId;
    }
    
    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }
    
    public String getSenderName() {
        return senderName;
    }
    
    public void setSenderName(String senderName) {
        this.senderName = senderName;
    }
    
    public String getSenderIp() {
        return senderIp;
    }
    
    public void setSenderIp(String senderIp) {
        this.senderIp = senderIp;
    }
    
    public int getSenderUdpPort() {
        return senderUdpPort;
    }
    
    public void setSenderUdpPort(int senderUdpPort) {
        this.senderUdpPort = senderUdpPort;
    }
    
    public int getSenderTcpPort() {
        return senderTcpPort;
    }
    
    public void setSenderTcpPort(int senderTcpPort) {
        this.senderTcpPort = senderTcpPort;
    }
    
    public int getTtl() {
        return ttl;
    }
    
    public void setTtl(int ttl) {
        this.ttl = ttl;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
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
    
    /**
     * DiscoverMessage'ı PeerInfo'ya dönüştürür (subnet bilgileri dahil)
     */
    public PeerInfo toPeerInfo() {
        PeerInfo peerInfo = new PeerInfo(senderId, senderName, senderIp, senderUdpPort, senderTcpPort, subnetId);
        peerInfo.setGatewayPeer(isGatewayPeer);
        return peerInfo;
    }
}

