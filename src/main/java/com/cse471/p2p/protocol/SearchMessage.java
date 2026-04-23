package com.cse471.p2p.protocol;

import java.util.List;

/**
 * Video arama mesajı (request ve reply)
 * Subnet-aware P2P network için genişletilmiş
 */
public class SearchMessage {
    private String type;           // SEARCH veya SEARCH_REPLY
    private String msgId;
    private String senderId;
    private String query;          // Arama kelimesi (request için)
    private List<VideoInfo> results; // Sonuçlar (reply için)
    private long timestamp;
    
    // Subnet-aware fields (opsiyonel, geriye uyumluluk için)
    private String subnetId;       // Gönderen peer'ın subnet'i
    private int ttl;               // Time-to-live / hop count (default: 3)
    
    // Sender bilgileri (SEARCH_REPLY göndermek için)
    private String senderIp;       // Gönderen peer'ın IP adresi
    private int senderUdpPort;     // Gönderen peer'ın UDP portu
    
    public SearchMessage() {
        this.ttl = 3;  // Default TTL
    }
    
    public SearchMessage(String type, String msgId, String senderId, String query) {
        this.type = type;
        this.msgId = msgId;
        this.senderId = senderId;
        this.query = query;
        this.timestamp = System.currentTimeMillis();
        this.ttl = 3;  // Default TTL
    }
    
    public SearchMessage(String type, String msgId, String senderId, String query, String subnetId, int ttl) {
        this.type = type;
        this.msgId = msgId;
        this.senderId = senderId;
        this.query = query;
        this.timestamp = System.currentTimeMillis();
        this.subnetId = subnetId;
        this.ttl = ttl;
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
    
    public String getQuery() {
        return query;
    }
    
    public void setQuery(String query) {
        this.query = query;
    }
    
    public List<VideoInfo> getResults() {
        return results;
    }
    
    public void setResults(List<VideoInfo> results) {
        this.results = results;
    }
    
    public long getTimestamp() {
        return timestamp;
    }
    
    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
    
    // Subnet-aware getters and setters
    public String getSubnetId() {
        return subnetId;
    }
    
    public void setSubnetId(String subnetId) {
        this.subnetId = subnetId;
    }
    
    public int getTtl() {
        return ttl;
    }
    
    public void setTtl(int ttl) {
        this.ttl = ttl;
    }
    
    // Sender bilgileri getters and setters
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
}

