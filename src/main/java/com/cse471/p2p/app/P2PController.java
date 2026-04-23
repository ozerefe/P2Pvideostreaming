package com.cse471.p2p.app;

import com.cse471.p2p.catalog.VideoCatalog;
import com.cse471.p2p.net.ChunkServer;
import com.cse471.p2p.net.DiscoveryService;
import com.cse471.p2p.protocol.PeerInfo;
import com.cse471.p2p.protocol.SearchMessage;
import com.cse471.p2p.protocol.VideoInfo;
import com.cse471.p2p.stream.DownloadSession;
import com.cse471.p2p.stream.PeerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ana kontrol sınıfı - tüm servisleri koordine eder
 */
public class P2PController {
    private static final Logger logger = LoggerFactory.getLogger(P2PController.class);
    
    private final String peerId;
    private final String peerName;
    private final int udpPort;
    private final int tcpPort;
    
    // Subnet bilgileri
    private final String subnetId;
    private final boolean isGatewayPeer;
    private final List<String> connectedSubnets;  // Gateway için bağlı subnet'ler
    
    // Core services
    private VideoCatalog videoCatalog;
    private DiscoveryService discoveryService;
    private ChunkServer chunkServer;
    private PeerManager peerManager;
    
    // Active download sessions
    private final Map<String, DownloadSession> activeSessions;  // fileHash -> session
    
    // Buffer folder
    private Path bufferFolder;
    
    // Connection status
    private volatile boolean connected;
    
    // Eski constructor (geriye uyumluluk için)
    public P2PController(String peerId, String peerName, int udpPort, int tcpPort) {
        this(peerId, peerName, udpPort, tcpPort, null, false, null);
    }
    
    // Yeni subnet-aware constructor
    public P2PController(String peerId, String peerName, int udpPort, int tcpPort,
                        String subnetId, boolean isGatewayPeer, List<String> connectedSubnets) {
        this.peerId = peerId;
        this.peerName = peerName;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.subnetId = subnetId;
        this.isGatewayPeer = isGatewayPeer;
        this.connectedSubnets = connectedSubnets != null ? new ArrayList<>(connectedSubnets) : new ArrayList<>();
        
        this.videoCatalog = new VideoCatalog();
        this.peerManager = new PeerManager();
        this.activeSessions = new ConcurrentHashMap<>();
        this.connected = false;
    }
    
    /**
     * Network'e bağlan
     */
    public void connect() throws IOException {
        if (connected) {
            logger.warn("Zaten bağlı");
            return;
        }
        
        logger.info("Network'e bağlanılıyor...");
        if (subnetId != null) {
            logger.info("Subnet: {}, Gateway: {}", subnetId, isGatewayPeer);
        }
        
        // Discovery service başlat (subnet-aware)
        discoveryService = new DiscoveryService(
            peerId, peerName, udpPort, tcpPort,
            subnetId, isGatewayPeer, connectedSubnets,
            () -> peerManager  // PeerManager supplier (gateway peer'ları bulmak için)
        );
        
        // Event listener'ları kaydet
        discoveryService.addPeerDiscoveredListener(this::handlePeerDiscovered);
        discoveryService.addSearchRequestListener(this::handleSearchRequest);
        discoveryService.addSearchReplyListener(this::handleSearchReply);
        
        discoveryService.start();
        
        // Chunk server başlat
        chunkServer = new ChunkServer(tcpPort, videoCatalog);
        chunkServer.start();
        
        connected = true;
        
        // Discovery broadcast yap
        discoveryService.broadcastDiscover();
        
        logger.info("Network'e bağlandı: UDP={}, TCP={}", udpPort, tcpPort);
    }
    
    /**
     * Network'ten ayrıl
     */
    public void disconnect() {
        if (!connected) {
            logger.warn("Zaten bağlı değil");
            return;
        }
        
        logger.info("Network'ten ayrılınıyor...");
        
        // Aktif session'ları durdur
        stopAllSessions();
        
        // Servisleri durdur
        if (discoveryService != null) {
            discoveryService.stop();
        }
        
        if (chunkServer != null) {
            chunkServer.stop();
        }
        
        // Peer listesini temizle
        peerManager.clear();
        
        connected = false;
        
        logger.info("Network'ten ayrıldı");
    }
    
    /**
     * Root video folder'ı ayarla
     */
    public void setRootFolder(Path rootFolder) throws IOException {
        logger.info("Root folder ayarlanıyor: {}", rootFolder);
        videoCatalog.setRootFolder(rootFolder);
        logger.info("{} video bulundu", videoCatalog.size());
    }
    
    /**
     * Buffer folder'ı ayarla
     */
    public void setBufferFolder(Path bufferFolder) {
        this.bufferFolder = bufferFolder;
        logger.info("Buffer folder ayarlandı: {}", bufferFolder);
    }
    
    /**
     * Video ara
     */
    public void searchVideos(String query) {
        if (!connected) {
            logger.warn("Bağlı değil, arama yapılamıyor");
            return;
        }
        
        logger.info("Video aranıyor: '{}'", query);
        discoveryService.broadcastSearch(query);
    }
    
    /**
     * Video streaming başlat
     */
    public DownloadSession startStreaming(VideoInfo videoInfo) throws IOException {
        if (!connected) {
            throw new IOException("Network'e bağlı değil");
        }
        
        if (bufferFolder == null) {
            throw new IOException("Buffer folder ayarlanmamış");
        }
        
        // Exclusion filter kontrolü - stream request kabul etmeden önce
        if (videoCatalog.isExcluded(videoInfo)) {
            logger.warn("Stream request reddedildi: Video exclusion filter'larına takıldı - {}", 
                videoInfo.getDisplayName());
            throw new IOException("Bu video exclusion filter'ları nedeniyle stream edilemez: " + 
                videoInfo.getDisplayName());
        }
        
        String fileHash = videoInfo.getFileHash();
        
        // Zaten aktif bir session var mı?
        if (activeSessions.containsKey(fileHash)) {
            logger.warn("Bu video için zaten aktif bir session var");
            return activeSessions.get(fileHash);
        }
        
        // Video için peer listesi al
        List<PeerInfo> peers = peerManager.getPeersForVideo(fileHash);
        if (peers.isEmpty()) {
            throw new IOException("Bu video için peer bulunamadı");
        }
        
        logger.info("Streaming başlatılıyor: {} ({} peer)", videoInfo.getDisplayName(), peers.size());
        
        // Output dosya yolu
        String fileName = fileHash.substring(0, 8) + videoInfo.getExtension();
        Path outputFile = bufferFolder.resolve(fileName);
        
        // Download session oluştur
        DownloadSession session = new DownloadSession(
            UUID.randomUUID().toString(),
            videoInfo,
            outputFile,
            peers
        );
        
        // Session'ı kaydet
        activeSessions.put(fileHash, session);
        
        // Session başlat
        session.start();
        
        logger.info("Streaming başladı: {}", videoInfo.getDisplayName());
        
        return session;
    }
    
    /**
     * Streaming'i durdur
     */
    public void stopStreaming(String fileHash) {
        DownloadSession session = activeSessions.remove(fileHash);
        if (session != null) {
            session.stop();
            logger.info("Streaming durduruldu: {}", session.getVideoInfo().getDisplayName());
        }
    }
    
    /**
     * Tüm session'ları durdur
     */
    public void stopAllSessions() {
        for (DownloadSession session : activeSessions.values()) {
            session.stop();
        }
        activeSessions.clear();
        logger.info("Tüm streaming session'ları durduruldu");
    }
    
    /**
     * Peer keşfedildiğinde çağrılır
     */
    private void handlePeerDiscovered(PeerInfo peerInfo) {
        boolean isNew = !peerManager.getAllPeers().containsKey(peerInfo.getPeerId());
        peerManager.addOrUpdatePeer(peerInfo);
        
        if (isNew) {
            logger.info("Yeni peer keşfedildi: {}", peerInfo);
        } else {
            logger.debug("Peer güncellendi: {}", peerInfo.getPeerName());
        }
    }
    
    /**
     * Search request alındığında çağrılır
     */
    private void handleSearchRequest(SearchMessage request) {
        logger.info("=== handleSearchRequest ÇAĞRILDI ===");
        logger.info("Query: '{}', SenderId: '{}', SenderIp: '{}', SenderUdpPort: {}", 
            request.getQuery(), request.getSenderId(), request.getSenderIp(), request.getSenderUdpPort());
        logger.info("Yerel catalog boyutu: {} video", videoCatalog.size());
        
        // Yerel catalog'da ara (exclusion filter'lar zaten search() içinde uygulanıyor)
        List<VideoInfo> results = videoCatalog.search(request.getQuery());
        
        // Ek olarak, exclusion filter'ları tekrar kontrol et (güvenlik için)
        results = results.stream()
            .filter(video -> !videoCatalog.isExcluded(video))
            .collect(java.util.stream.Collectors.toList());
        
        logger.info("Arama sonucu: {} video bulundu (exclusion filter'lar uygulandı)", results.size());
        
        if (!results.isEmpty()) {
            // Sonuçları gönder
            PeerInfo requester = peerManager.getPeer(request.getSenderId());
            
            String targetIp;
            int targetPort;
            
            if (requester != null) {
                // Requester peer bulundu - peer bilgilerini kullan
                targetIp = requester.getIpAddress();
                targetPort = requester.getUdpPort();
            } else if (request.getSenderIp() != null && !request.getSenderIp().isEmpty()) {
                // ✅ DÜZELTME: Peer bulunamadı ama sender IP bilgisi var - direkt gönder
                targetIp = request.getSenderIp();
                // UDP port bilgisi yoksa, bilinen portları dene
                targetPort = request.getSenderUdpPort() > 0 ? request.getSenderUdpPort() : 50000;
                logger.info("Peer bulunamadı ama sender IP mevcut, direkt gönderiliyor: {}:{}", targetIp, targetPort);
            } else {
                // Hiçbir bilgi yok, gönderemiyoruz
                logger.warn("Search request alındı ama requester bilgisi bulunamadı (senderId: {})", 
                    request.getSenderId());
                return;
            }
            
            // SEARCH_REPLY gönder
            discoveryService.sendSearchReply(request, results, targetIp, targetPort);
            
            // Eğer port bilgisi yoksa, diğer bilinen portlara da gönder
            if (request.getSenderUdpPort() == 0 && request.getSenderIp() != null) {
                int[] knownPorts = {50000, 50010, 50020};
                for (int port : knownPorts) {
                    if (port != targetPort) {
                        discoveryService.sendSearchReply(request, results, targetIp, port);
                    }
                }
            }
                
            logger.info("Search reply gönderildi: {} sonuç (query: '{}', target: {}:{})", 
                results.size(), request.getQuery(), targetIp, targetPort);
        }
    }
    
    /**
     * Search sonuçlarını handle et (GUI'dan çağrılır)
     * Exclusion filter'lar uygulanır
     */
    public void handleSearchReply(SearchMessage reply) {
        logger.info("=== handleSearchReply ÇAĞRILDI ===");
        String senderId = reply.getSenderId();
        List<VideoInfo> results = reply.getResults();
        
        logger.info("SenderId: '{}', Results: {}", senderId, results != null ? results.size() : "null");
        
        if (results != null && !results.isEmpty()) {
            // Exclusion filter'ları uygula - GUI'da gösterilmeden önce
            List<VideoInfo> filteredResults = results.stream()
                .filter(video -> !videoCatalog.isExcluded(video))
                .collect(java.util.stream.Collectors.toList());
            
            int excludedCount = results.size() - filteredResults.size();
            if (excludedCount > 0) {
                logger.info("{} video exclusion filter'ları nedeniyle filtrelendi", excludedCount);
            }
            
            // Filtrelenmiş sonuçları kaydet
            peerManager.addPeerVideos(senderId, filteredResults);
            logger.info("{} peer'ından {} video sonucu alındı ve kaydedildi ({} video filtrelendi)", 
                senderId, filteredResults.size(), excludedCount);
        } else {
            logger.warn("SEARCH_REPLY alındı ama sonuç yok veya boş!");
        }
    }
    
    // Getters
    public VideoCatalog getVideoCatalog() {
        return videoCatalog;
    }
    
    public PeerManager getPeerManager() {
        return peerManager;
    }
    
    public Map<String, DownloadSession> getActiveSessions() {
        return new HashMap<>(activeSessions);
    }
    
    public boolean isConnected() {
        return connected;
    }
    
    public String getPeerId() {
        return peerId;
    }
    
    public String getPeerName() {
        return peerName;
    }
    
    public Path getBufferFolder() {
        return bufferFolder;
    }
    
    public int getUdpPort() {
        return udpPort;
    }
    
    public int getTcpPort() {
        return tcpPort;
    }
}

