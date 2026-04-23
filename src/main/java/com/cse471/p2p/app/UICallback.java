package com.cse471.p2p.app;

import com.cse471.p2p.protocol.VideoInfo;

import java.util.List;

/**
 * UI callback interface - core logic'in UI'yi güncellemesi için
 */
public interface UICallback {
    
    /**
     * Log mesajı göster
     */
    void onLog(String message);
    
    /**
     * Search sonuçları geldiğinde
     */
    void onSearchResults(List<VideoInfo> results);
    
    /**
     * Stream progress güncellemesi
     */
    void onStreamProgress(String videoId, String peerIp, double percent, String status);
    
    /**
     * Buffer durumu güncellemesi
     */
    void onBufferStatus(String videoId, double percentBuffered);
    
    /**
     * Connection durumu değiştiğinde
     */
    void onConnectionStatusChanged(boolean connected);
}

