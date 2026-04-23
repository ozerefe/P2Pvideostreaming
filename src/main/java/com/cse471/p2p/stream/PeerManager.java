package com.cse471.p2p.stream;

import com.cse471.p2p.protocol.PeerInfo;
import com.cse471.p2p.protocol.VideoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Peer listesi ve video-peer mapping yönetimi
 */
public class PeerManager {
    private static final Logger logger = LoggerFactory.getLogger(PeerManager.class);
    
    private static final long PEER_TIMEOUT_MS = 15000;  // 15 saniye (disconnect hemen yansısın)
    
    // Peer listesi
    private final Map<String, PeerInfo> peers;  // peerId -> PeerInfo
    
    // Video-Peer mapping (hangi video hangi peer'da var)
    private final Map<String, Set<String>> videoPeers;  // fileHash -> Set<peerId>
    
    // Video metadata
    private final Map<String, VideoInfo> videoMetadata;  // fileHash -> VideoInfo
    
    // Cleanup scheduler
    private final ScheduledExecutorService cleanupScheduler;
    private ScheduledFuture<?> cleanupTask;
    
    public PeerManager() {
        this.peers = new ConcurrentHashMap<>();
        this.videoPeers = new ConcurrentHashMap<>();
        this.videoMetadata = new ConcurrentHashMap<>();
        this.cleanupScheduler = Executors.newScheduledThreadPool(1);
        
        // Her 5 saniyede bir timeout kontrolü
        this.cleanupTask = cleanupScheduler.scheduleAtFixedRate(
            this::cleanupTimeoutPeers, 
            5, 5, TimeUnit.SECONDS
        );
        
        logger.debug("PeerManager başlatıldı (timeout: {}ms, cleanup: 5s)", PEER_TIMEOUT_MS);
    }
    
    /**
     * Peer ekler veya günceller
     */
    public void addOrUpdatePeer(PeerInfo peerInfo) {
        String peerId = peerInfo.getPeerId();
        
        PeerInfo existing = peers.get(peerId);
        if (existing != null) {
            existing.updateLastSeen();
            
            // ✅ DÜZELTME: Subnet bilgisini güncelle (eğer yeni bilgi varsa ve farklıysa)
            // ÖNEMLİ: Gateway forward ederken subnetId'yi değiştirebilir, bu yüzden
            // sadece yeni subnet bilgisi null değilse ve mevcut subnet bilgisi null veya farklıysa güncelle
            // Ama dikkat: Gateway forward etmişse, yeni subnet bilgisi gateway'in subnet'i olabilir
            // Bu durumda, eğer mevcut subnet bilgisi zaten varsa ve gateway'in subnet'i değilse, koru
            if (peerInfo.getSubnetId() != null) {
                // Yeni subnet bilgisi var
                if (existing.getSubnetId() == null) {
                    // Mevcut subnet bilgisi yok, yeni bilgiyi ekle
                    existing.setSubnetId(peerInfo.getSubnetId());
                    logger.debug("Peer subnet bilgisi güncellendi: {} -> {}", 
                        peerInfo.getPeerName(), peerInfo.getSubnetId());
                } else if (!existing.getSubnetId().equals(peerInfo.getSubnetId())) {
                    // Mevcut ve yeni subnet bilgisi farklı
                    // Gateway forward etmiş olabilir, bu durumda mevcut bilgiyi koru
                    // Ama eğer mevcut bilgi gateway'in subnet'i ise, yeni bilgiyi kullan
                    // (Bu durumda, yeni bilgi orijinal subnet olabilir)
                    // Basit çözüm: Her zaman yeni subnet bilgisini kullan (en son gelen bilgi)
                    existing.setSubnetId(peerInfo.getSubnetId());
                    logger.debug("Peer subnet bilgisi değiştirildi: {} -> {} (peer: {})", 
                        existing.getSubnetId(), peerInfo.getSubnetId(), peerInfo.getPeerName());
                }
            }
            
            // Gateway bilgisini güncelle
            if (peerInfo.isGatewayPeer()) {
                existing.setGatewayPeer(true);
                if (peerInfo.getConnectedSubnets() != null && !peerInfo.getConnectedSubnets().isEmpty()) {
                    existing.setConnectedSubnets(peerInfo.getConnectedSubnets());
                }
            }
            
            logger.debug("Peer güncellendi: {}", peerInfo.getPeerName());
        } else {
            peers.put(peerId, peerInfo);
            logger.info("Yeni peer eklendi: {} ({}:{}, subnet={})", 
                peerInfo.getPeerName(), peerInfo.getIpAddress(), peerInfo.getTcpPort(), 
                peerInfo.getSubnetId() != null ? peerInfo.getSubnetId() : "unknown");
        }
    }
    
    /**
     * Peer'ı siler
     */
    public void removePeer(String peerId) {
        PeerInfo removed = peers.remove(peerId);
        if (removed != null) {
            // Bu peer'ın tüm video kayıtlarını temizle
            videoPeers.values().forEach(set -> set.remove(peerId));
            logger.info("Peer silindi: {}", removed.getPeerName());
        }
    }
    
    /**
     * Aktif peer listesini döndürür
     */
    public List<PeerInfo> getActivePeers() {
        long now = System.currentTimeMillis();
        return peers.values().stream()
            .filter(peer -> now - peer.getLastSeen() < PEER_TIMEOUT_MS)
            .collect(Collectors.toList());
    }
    
    /**
     * Tüm peer map'ini döndürür (internal use)
     */
    public Map<String, PeerInfo> getAllPeers() {
        return new HashMap<>(peers);
    }
    
    /**
     * Belirli bir peer'ı döndürür
     */
    public PeerInfo getPeer(String peerId) {
        return peers.get(peerId);
    }
    
    /**
     * Bir peer'ın videolarını ekler
     */
    public void addPeerVideos(String peerId, List<VideoInfo> videos) {
        for (VideoInfo video : videos) {
            String fileHash = video.getFileHash();
            
            // Video metadata'sını sakla
            videoMetadata.putIfAbsent(fileHash, video);
            
            // Video-peer mapping'i güncelle
            videoPeers.computeIfAbsent(fileHash, k -> ConcurrentHashMap.newKeySet()).add(peerId);
        }
        
        logger.debug("Peer {} için {} video kaydedildi", peerId, videos.size());
    }
    
    /**
     * Belirli bir video için mevcut peer'ları döndürür
     */
    public List<PeerInfo> getPeersForVideo(String fileHash) {
        Set<String> peerIds = videoPeers.get(fileHash);
        if (peerIds == null || peerIds.isEmpty()) {
            return Collections.emptyList();
        }
        
        return peerIds.stream()
            .map(peers::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Video metadata'sını döndürür
     */
    public VideoInfo getVideoMetadata(String fileHash) {
        return videoMetadata.get(fileHash);
    }
    
    /**
     * Tüm mevcut videoları döndürür (unique)
     */
    public List<VideoInfo> getAllAvailableVideos() {
        return new ArrayList<>(videoMetadata.values());
    }
    
    /**
     * Video arar
     */
    public List<VideoInfo> searchVideos(String query) {
        if (query == null || query.trim().isEmpty()) {
            return getAllAvailableVideos();
        }
        
        String lowerQuery = query.toLowerCase();
        return videoMetadata.values().stream()
            .filter(video -> video.getDisplayName().toLowerCase().contains(lowerQuery))
            .collect(Collectors.toList());
    }
    
    /**
     * Timeout olan peer'ları temizler
     */
    public void cleanupTimeoutPeers() {
        long now = System.currentTimeMillis();
        List<String> timeoutPeers = new ArrayList<>();
        
        for (Map.Entry<String, PeerInfo> entry : peers.entrySet()) {
            if (now - entry.getValue().getLastSeen() > PEER_TIMEOUT_MS) {
                timeoutPeers.add(entry.getKey());
            }
        }
        
        for (String peerId : timeoutPeers) {
            removePeer(peerId);
        }
        
        if (!timeoutPeers.isEmpty()) {
            logger.info("{} timeout peer temizlendi", timeoutPeers.size());
        }
    }
    
    /**
     * Tüm verileri temizler
     */
    public void clear() {
        peers.clear();
        videoPeers.clear();
        videoMetadata.clear();
        logger.info("PeerManager temizlendi");
    }
    
    /**
     * Kapatma - cleanup scheduler'ı durdurur
     */
    public void shutdown() {
        if (cleanupTask != null) {
            cleanupTask.cancel(false);
        }
        cleanupScheduler.shutdownNow();
        logger.debug("PeerManager kapatıldı");
    }
    
    /**
     * İstatistikler
     */
    public int getPeerCount() {
        return getActivePeers().size();
    }
    
    public int getVideoCount() {
        return videoMetadata.size();
    }
    
    // ========== Subnet-aware metodlar ==========
    
    /**
     * Belirli bir subnet'teki aktif peer'ları döndürür
     */
    public List<PeerInfo> getPeersInSubnet(String subnetId) {
        long now = System.currentTimeMillis();
        return peers.values().stream()
            .filter(peer -> peer.getSubnetId() != null && peer.getSubnetId().equals(subnetId))
            .filter(peer -> now - peer.getLastSeen() < PEER_TIMEOUT_MS)
            .collect(Collectors.toList());
    }
    
    /**
     * Gateway peer'ları döndürür (subnet'ler arası bağlantı için)
     */
    public List<PeerInfo> getGatewayPeers() {
        long now = System.currentTimeMillis();
        return peers.values().stream()
            .filter(PeerInfo::isGatewayPeer)
            .filter(peer -> now - peer.getLastSeen() < PEER_TIMEOUT_MS)
            .collect(Collectors.toList());
    }
    
    /**
     * Belirli bir subnet'e bağlı gateway peer'ları döndürür
     */
    public List<PeerInfo> getGatewayPeersForSubnet(String subnetId) {
        long now = System.currentTimeMillis();
        return peers.values().stream()
            .filter(PeerInfo::isGatewayPeer)
            .filter(peer -> peer.isConnectedToSubnet(subnetId))
            .filter(peer -> now - peer.getLastSeen() < PEER_TIMEOUT_MS)
            .collect(Collectors.toList());
    }
    
    /**
     * İki peer aynı subnet'te mi?
     */
    public boolean isInSameSubnet(String peerId1, String peerId2) {
        PeerInfo peer1 = peers.get(peerId1);
        PeerInfo peer2 = peers.get(peerId2);
        if (peer1 == null || peer2 == null) {
            return false;
        }
        return peer1.isInSameSubnet(peer2);
    }
    
    /**
     * Peer'ın subnet'ini döndürür
     */
    public String getPeerSubnet(String peerId) {
        PeerInfo peer = peers.get(peerId);
        return peer != null ? peer.getSubnetId() : null;
    }
    
    /**
     * Tüm bilinen subnet'leri döndürür
     */
    public Set<String> getAllSubnets() {
        return peers.values().stream()
            .map(PeerInfo::getSubnetId)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
    }
}

