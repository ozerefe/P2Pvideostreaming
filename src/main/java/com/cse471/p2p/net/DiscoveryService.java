package com.cse471.p2p.net;

import com.cse471.p2p.protocol.*;
import com.cse471.p2p.util.NetworkUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import com.cse471.p2p.stream.PeerManager;

/**
 * UDP tabanlı peer discovery servisi
 * Limited-scope flooding ile TTL kontrollü yayılım
 * Subnet-aware P2P network desteği ile genişletilmiş
 */
public class DiscoveryService {
    private static final Logger logger = LoggerFactory.getLogger(DiscoveryService.class);
    
    private static final int MAX_PACKET_SIZE = 8192;
    private static final int DEFAULT_TTL = 3;  // Maksimum hop sayısı
    private static final long MESSAGE_EXPIRY_MS = 30000;  // 30 saniye
    
    private final String peerId;
    private final String peerName;
    private final int udpPort;
    private final int tcpPort;
    
    // Subnet bilgileri
    private final String subnetId;
    private final boolean isGatewayPeer;
    private final List<String> connectedSubnets;  // Gateway için bağlı subnet'ler
    private final java.util.function.Supplier<com.cse471.p2p.stream.PeerManager> peerManagerSupplier;  // Gateway peer'ları bulmak için
    
    private DatagramSocket socket;
    private Thread listenerThread;
    private ExecutorService executor;
    private volatile boolean running;
    
    // Periyodik discovery için
    private ScheduledExecutorService discoveryScheduler;
    
    // Görülen mesajları track et (döngüyü önle)
    private final Map<String, Long> seenMessages;  // msgId -> timestamp
    
    // Event listeners
    private final List<Consumer<PeerInfo>> peerDiscoveredListeners;
    private final List<Consumer<SearchMessage>> searchRequestListeners;
    private final List<Consumer<SearchMessage>> searchReplyListeners;
    
    // Eski constructor (geriye uyumluluk için)
    public DiscoveryService(String peerId, String peerName, int udpPort, int tcpPort) {
        this(peerId, peerName, udpPort, tcpPort, null, false, null, null);
    }
    
    // Yeni subnet-aware constructor
    public DiscoveryService(String peerId, String peerName, int udpPort, int tcpPort,
                           String subnetId, boolean isGatewayPeer, 
                           List<String> connectedSubnets,
                           java.util.function.Supplier<com.cse471.p2p.stream.PeerManager> peerManagerSupplier) {
        this.peerId = peerId;
        this.peerName = peerName;
        this.udpPort = udpPort;
        this.tcpPort = tcpPort;
        this.subnetId = subnetId;
        this.isGatewayPeer = isGatewayPeer;
        this.connectedSubnets = connectedSubnets != null ? new ArrayList<>(connectedSubnets) : new ArrayList<>();
        this.peerManagerSupplier = peerManagerSupplier;
        this.seenMessages = new ConcurrentHashMap<>();
        this.peerDiscoveredListeners = new CopyOnWriteArrayList<>();
        this.searchRequestListeners = new CopyOnWriteArrayList<>();
        this.searchReplyListeners = new CopyOnWriteArrayList<>();
        this.executor = Executors.newCachedThreadPool();
        this.discoveryScheduler = Executors.newScheduledThreadPool(1);
    }
    
    /**
     * Discovery servisini başlatır
     */
    public void start() throws IOException {
        if (running) {
            logger.warn("Discovery servisi zaten çalışıyor");
            return;
        }
        
        // Socket'i TÜM interface'lere (0.0.0.0) açıkça bind et
        // Bu, VirtualBox'ta farklı interface'lerden gelen paketleri almak için kritik
        InetSocketAddress bindAddr = new InetSocketAddress("0.0.0.0", udpPort);
        socket = new DatagramSocket(null); // Önce bind etmeden oluştur
        socket.setReuseAddress(true); // Aynı makinede multiple instance için
        socket.setBroadcast(true);
        socket.bind(bindAddr); // Tüm interface'lere bind et
        
        logger.info("Socket 0.0.0.0:{} adresine bind edildi, broadcast={}", udpPort, socket.getBroadcast());
        
        // Loopback için özel ayar
        try {
            socket.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, true);
        } catch (Exception e) {
            logger.warn("Multicast loop ayarlanamadı: {}", e.getMessage());
        }
        
        running = true;
        
        // Listener thread
        listenerThread = new Thread(this::listen, "UDP-Discovery-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
        
        // Cleanup thread (eski mesajları temizle)
        executor.submit(this::cleanupTask);
        
        // İlk discovery broadcast
        broadcastDiscover();
        
        // Periyodik discovery broadcast (her 10 saniyede bir)
        discoveryScheduler.scheduleAtFixedRate(() -> {
            try {
                broadcastDiscover();
                logger.debug("Periyodik discovery broadcast gönderildi");
            } catch (Exception e) {
                logger.error("Periyodik discovery hatası", e);
            }
        }, 10, 10, TimeUnit.SECONDS);
        
        logger.info("Discovery servisi başlatıldı: UDP port {} (multicast mode + periyodik broadcast)", udpPort);
    }
    
    /**
     * Discovery servisini durdurur
     */
    public void stop() {
        running = false;
        
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        
        if (listenerThread != null) {
            listenerThread.interrupt();
        }
        
        executor.shutdown();
        
        if (discoveryScheduler != null) {
            discoveryScheduler.shutdown();
        }
        
        seenMessages.clear();
        
        logger.info("Discovery servisi durduruldu");
    }
    
    /**
     * DISCOVER mesajı broadcast eder (subnet-aware)
     */
    public void broadcastDiscover() {
        String msgId = UUID.randomUUID().toString();
        
        DiscoverMessage msg = new DiscoverMessage(
            MessageType.DISCOVER.name(),
            msgId,
            peerId,
            peerName,
            NetworkUtils.getLocalIPAddress(),
            udpPort,
            tcpPort,
            DEFAULT_TTL,
            subnetId,
            isGatewayPeer
        );
        
        // Kendi mesajımızı görüldü olarak işaretle
        seenMessages.put(msgId, System.currentTimeMillis());
        
        // Subnet-aware broadcast gönder
        if (subnetId != null) {
            // Subnet-aware broadcast: önce kendi subnet'ine, sonra gateway'ler üzerinden diğer subnet'lere
            sendSubnetAwareBroadcast(msg);
        } else {
            // Eski davranış (geriye uyumluluk)
        sendBroadcast(msg);
        sendToKnownPorts(msg);
        }
        
        logger.debug("DISCOVER mesajı broadcast edildi (TTL={}, subnet={})", DEFAULT_TTL, subnetId);
    }
    
    /**
     * Subnet-aware broadcast gönderir
     * 1. Kendi subnet'ine normal broadcast
     * 2. Localhost test için: aynı subnet'teki olası portlara gönder
     * 3. Gateway ise: Bağlı subnet'lere de direkt gönder (localhost test için)
     * 4. Gateway peer'lar üzerinden diğer subnet'lere forward
     */
    private void sendSubnetAwareBroadcast(DiscoverMessage msg) {
        // 1. Kendi subnet'ine broadcast (normal broadcast)
        sendBroadcast(msg);
        
        // 2. Localhost test için: aynı subnet'teki olası portlara gönder
        // (Subnet kontrolü alıcı tarafta yapılacak - handleDiscover'da)
        sendToKnownPortsSubnetAware(msg);
        
        // 3. Gateway ise: Bilinen peer IP'lerine doğrudan unicast gönder
        // NOT: Eski broadcast bloğu kaldırıldı - sendBroadcast() zaten tüm broadcast adreslerine
        // orijinal mesajı gönderiyor ve unicast de eklenmiş durumda
        if (isGatewayPeer && !connectedSubnets.isEmpty()) {
            logger.debug("Gateway olarak bağlı subnet'lere unicast gönderimi yapılıyor: {}", connectedSubnets);
        }
        
        // 4. Gateway peer'lar varsa, onlar üzerinden diğer subnet'lere forward et
        if (isGatewayPeer && peerManagerSupplier != null) {
            PeerManager peerManager = peerManagerSupplier.get();
            if (peerManager != null) {
                // Bağlı olduğumuz subnet'lerdeki gateway peer'lara gönder
                for (String connectedSubnet : connectedSubnets) {
                    if (!connectedSubnet.equals(subnetId)) {
                        forwardToSubnetViaGateway(msg, connectedSubnet, peerManager);
                    }
                }
            }
        }
    }
    
    /**
     * Gateway peer'lar üzerinden belirtilen subnet'e mesaj forward eder
     */
    private void forwardToSubnetViaGateway(DiscoverMessage msg, String targetSubnet, PeerManager peerManager) {
        List<PeerInfo> gatewayPeers = peerManager.getGatewayPeersForSubnet(targetSubnet);
        if (gatewayPeers.isEmpty()) {
            // Gateway peer henüz keşfedilmemiş - localhost test için direkt portlara gönder
            // (Gerçek network'te bu durumda mesaj kaybolur, ama localhost test için yardımcı)
            logger.debug("Gateway peer bulunamadı subnet {} için, localhost test için direkt gönderim deneniyor", targetSubnet);
            // Localhost test için: hedef subnet'teki olası portlara gönder
            // (Subnet kontrolü alıcı tarafta yapılacak)
            sendToKnownPortsSubnetAware(msg);
        } else {
            for (PeerInfo gateway : gatewayPeers) {
                if (!gateway.getPeerId().equals(peerId)) {
                    DiscoverMessage forwardedMsg = new DiscoverMessage(
                        msg.getType(),
                        msg.getMsgId(),
                        msg.getSenderId(),
                        msg.getSenderName(),
                        msg.getSenderIp(),
                        msg.getSenderUdpPort(),
                        msg.getSenderTcpPort(),
                        msg.getTtl(),
                        msg.getSubnetId(),
                        msg.isGatewayPeer()
                    );
                    sendUnicast(forwardedMsg, gateway.getIpAddress(), gateway.getUdpPort());
                    logger.debug("DISCOVER mesajı gateway üzerinden forward edildi: {} -> subnet {}", 
                        gateway.getPeerName(), targetSubnet);
                }
            }
        }
    }
    
    /**
     * Localhost'ta bilinen portlara direkt mesaj gönderir
     * Subnet bilgisi yoksa eski davranış (geriye uyumluluk)
     */
    private void sendToKnownPorts(Object message) {
        // Subnet bilgisi yoksa eski davranış
        if (subnetId == null) {
        // Test için bilinen peer portları
        int[] knownPorts = {50000, 50010, 50020};
        
        try {
            String json = MessageSerializer.toJson(message);
            if (json == null) return;
            
            byte[] data = json.getBytes();
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            
            for (int port : knownPorts) {
                if (port == udpPort) continue; // Kendine gönderme
                
                try {
                    DatagramPacket packet = new DatagramPacket(data, data.length, localhost, port);
                    socket.send(packet);
                        logger.debug("Localhost mesajı gönderildi: port {} (subnet bilgisi yok)", port);
                } catch (IOException e) {
                    // Port dinlemiyor olabilir, sorun değil
                    logger.debug("Port {} erişilemez", port);
                }
            }
        } catch (Exception e) {
            logger.debug("Localhost gönderim hatası: {}", e.getMessage());
        }
        }
        // Subnet-aware modda sendToKnownPortsSubnetAware() kullanılır
    }
    
    /**
     * Localhost test için subnet-aware port gönderimi
     * Tüm olası portlara gönderir, subnet kontrolü alıcı tarafta yapılır
     */
    private void sendToKnownPortsSubnetAware(Object message) {
        // Localhost test için: 50000-50200 arası portlara gönder
        // Subnet kontrolü handleDiscover()'da yapılacak
        try {
            String json = MessageSerializer.toJson(message);
            if (json == null) return;
            
            byte[] data = json.getBytes();
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            
            // Localhost test için port aralığı (50000-50200)
            for (int port = 50000; port <= 50200; port += 10) {
                if (port == udpPort) continue; // Kendine gönderme
                
                try {
                    DatagramPacket packet = new DatagramPacket(data, data.length, localhost, port);
                    socket.send(packet);
                    logger.debug("Localhost mesajı gönderildi: port {} (subnet-aware, alıcı kontrol edecek)", port);
                } catch (IOException e) {
                    // Port dinlemiyor olabilir, sorun değil
                }
            }
        } catch (Exception e) {
            logger.debug("Localhost subnet-aware gönderim hatası: {}", e.getMessage());
        }
    }
    
    /**
     * SEARCH mesajı broadcast eder (subnet-aware)
     */
    public void broadcastSearch(String query) {
        String msgId = UUID.randomUUID().toString();
        
        SearchMessage msg = new SearchMessage(
            MessageType.SEARCH.name(),
            msgId,
            peerId,
            query,
            subnetId,
            DEFAULT_TTL  // TTL ile sınırlandır
        );
        
        // Kendi mesajımızı görüldü olarak işaretle
        seenMessages.put(msgId, System.currentTimeMillis());
        
        // Subnet-aware broadcast gönder
        if (subnetId != null) {
            sendSubnetAwareSearchBroadcast(msg);
        } else {
            // Eski davranış (geriye uyumluluk)
            sendBroadcast(msg);
            sendToKnownPorts(msg);
        }
        
        // ✅ EK: Bilinen tüm peer'lara doğrudan SEARCH mesajı gönder
        sendSearchToAllKnownPeers(msg);
        
        logger.debug("SEARCH mesajı broadcast edildi: '{}' (subnet={}, TTL={})", query, subnetId, DEFAULT_TTL);
    }
    
    /**
     * Bilinen tüm peer'lara SEARCH mesajı gönderir
     * Gateway ise: Farklı subnet'lere o subnet'in ID'si ile gönderir
     */
    private void sendSearchToAllKnownPeers(SearchMessage msg) {
        String myLocalIp = NetworkUtils.getLocalIPAddress();
        logger.debug("sendSearchToAllKnownPeers: myLocalIp={}, udpPort={}, isGateway={}", myLocalIp, udpPort, isGatewayPeer);
        
        // 1. Bilinen peer'lara gönder (peerManager varsa)
        if (peerManagerSupplier != null) {
            PeerManager pm = peerManagerSupplier.get();
            if (pm != null) {
                for (PeerInfo peer : pm.getAllPeers().values()) {
                    if (peer.getPeerId().equals(peerId)) continue;
                    
                    // Gateway ise ve peer farklı subnet'te ise, o subnet'in ID'si ile mesaj oluştur
                    SearchMessage msgToSend = msg;
                    if (isGatewayPeer && peer.getSubnetId() != null && !peer.getSubnetId().equals(subnetId)) {
                        msgToSend = new SearchMessage(
                            msg.getType(), msg.getMsgId(), msg.getSenderId(), msg.getQuery(),
                            peer.getSubnetId(),  // Hedef peer'ın subnet ID'si
                            msg.getTtl()
                        );
                        logger.debug("Gateway: SEARCH mesajı subnet değiştirilerek gönderiliyor: {} -> {}", 
                            subnetId, peer.getSubnetId());
                    }
                    
                    try {
                        String json = MessageSerializer.toJson(msgToSend);
                        byte[] data = json.getBytes();
                        InetAddress addr = InetAddress.getByName(peer.getIpAddress());
                        DatagramPacket packet = new DatagramPacket(data, data.length, addr, peer.getUdpPort());
                        socket.send(packet);
                        logger.debug("SEARCH unicast peer'a gönderildi: {} -> {}:{} (subnet={})", 
                            msg.getMsgId().substring(0, 8), peer.getIpAddress(), peer.getUdpPort(), msgToSend.getSubnetId());
                    } catch (Exception e) {
                        logger.warn("SEARCH unicast hatası {}: {}", peer.getIpAddress(), e.getMessage());
                    }
                }
            } else {
                logger.warn("sendSearchToAllKnownPeers: PeerManager null!");
            }
        }
        
        // 2. Hardcoded IP'lere gönder - Gateway ise subnet'e göre doğru subnetId ile
        String[] subnetAIPs = {"192.168.1.10", "192.168.1.20"};
        String[] subnetBIPs = {"192.168.2.10", "192.168.2.20"};
        int[] knownPorts = {50000, 50010, 50020};
        
        logger.debug("SEARCH hardcoded unicast başlıyor, isGateway={}, connectedSubnets={}", isGatewayPeer, connectedSubnets);
        
        // Subnet-A IP'lerine gönder
        sendSearchToIPs(msg, subnetAIPs, knownPorts, myLocalIp, "Subnet-A");
        
        // Subnet-B IP'lerine gönder
        sendSearchToIPs(msg, subnetBIPs, knownPorts, myLocalIp, "Subnet-B");
    }
    
    /**
     * Belirli IP'lere SEARCH mesajı gönderir
     * Gateway ise ve hedef farklı subnet ise, subnetId'yi değiştirir
     */
    private void sendSearchToIPs(SearchMessage msg, String[] ips, int[] ports, String myLocalIp, String targetSubnet) {
        // Gateway ise ve hedef subnet farklı ise, mesajı o subnet ID'si ile gönder
        SearchMessage msgToSend = msg;
        if (isGatewayPeer && !targetSubnet.equals(subnetId)) {
            msgToSend = new SearchMessage(
                msg.getType(), msg.getMsgId(), msg.getSenderId(), msg.getQuery(),
                targetSubnet,  // Hedef subnet ID
                msg.getTtl()
            );
            logger.info("Gateway: Hardcoded SEARCH için subnet değiştiriliyor: {} -> {}", subnetId, targetSubnet);
        }
        
        String json = MessageSerializer.toJson(msgToSend);
        if (json == null) return;
        byte[] data = json.getBytes();
        
        for (String ip : ips) {
            if (ip.equals(myLocalIp)) continue;
            for (int port : ports) {
                if (port == udpPort) continue;
                try {
                    InetAddress addr = InetAddress.getByName(ip);
                    DatagramPacket packet = new DatagramPacket(data, data.length, addr, port);
                    socket.send(packet);
                    logger.debug("SEARCH hardcoded unicast OK: {}:{} (subnet={})", ip, port, msgToSend.getSubnetId());
                } catch (Exception e) {
                    logger.warn("SEARCH hardcoded unicast HATA {}:{} - {}", ip, port, e.getMessage());
                }
            }
        }
    }
    
    /**
     * Subnet-aware SEARCH broadcast gönderir
     */
    private void sendSubnetAwareSearchBroadcast(SearchMessage msg) {
        // 1. Kendi subnet'ine broadcast
        sendBroadcast(msg);
        sendToKnownPortsSubnetAware(msg);
        
        // 2. Gateway ise: sendBroadcast() zaten tüm broadcast adreslerine + unicast gönderiyor
        if (isGatewayPeer && !connectedSubnets.isEmpty()) {
            logger.debug("Gateway olarak SEARCH için bağlı subnet'lere unicast gönderildi: {}", connectedSubnets);
        }
        
        // 3. Gateway peer'lar varsa, onlar üzerinden diğer subnet'lere forward et
        // (Bu, başka gateway peer'ları bulmak için - kendimiz gateway isek yukarıdaki kod çalışır)
        if (isGatewayPeer && peerManagerSupplier != null) {
            PeerManager peerManager = peerManagerSupplier.get();
            if (peerManager != null) {
                for (String connectedSubnet : connectedSubnets) {
                    if (!connectedSubnet.equals(subnetId)) {
                        forwardSearchToSubnetViaGateway(msg, connectedSubnet, peerManager);
                    }
                }
            }
        }
    }
    
    /**
     * Gateway peer'lar üzerinden belirtilen subnet'e SEARCH mesajı forward eder
     */
    private void forwardSearchToSubnetViaGateway(SearchMessage msg, String targetSubnet, PeerManager peerManager) {
        List<PeerInfo> gatewayPeers = peerManager.getGatewayPeersForSubnet(targetSubnet);
        if (gatewayPeers.isEmpty()) {
            // Gateway peer henüz keşfedilmemiş - localhost test için direkt portlara gönder
            logger.debug("Gateway peer bulunamadı subnet {} için, localhost test için direkt gönderim deneniyor", targetSubnet);
            sendToKnownPortsSubnetAware(msg);
        } else {
            for (PeerInfo gateway : gatewayPeers) {
                if (!gateway.getPeerId().equals(peerId)) {
                    SearchMessage forwardedMsg = new SearchMessage(
                        msg.getType(),
                        msg.getMsgId(),
                        msg.getSenderId(),
                        msg.getQuery(),
                        msg.getSubnetId(),
                        msg.getTtl()
                    );
                    sendUnicast(forwardedMsg, gateway.getIpAddress(), gateway.getUdpPort());
                    logger.debug("SEARCH mesajı gateway üzerinden forward edildi: {} -> subnet {}", 
                        gateway.getPeerName(), targetSubnet);
                }
            }
        }
    }
    
    /**
     * Broadcast mesaj gönderir - tüm subnet broadcast adreslerine tüm bilinen portlara gönderir
     */
    private void sendBroadcast(Object message) {
        try {
            String json = MessageSerializer.toJson(message);
            if (json == null || json.trim().isEmpty()) {
                logger.error("Serialization başarısız - boş JSON");
                return;
            }
            
            byte[] data = json.getBytes();
            
            // Tüm bilinen portlara gönder (50000, 50010, 50020, vb.)
            int[] knownPorts = {50000, 50010, 50020, 50030, 50040, 50050, 50060, 50070, 50080, 50090};
            
            // YÖNTEM 1: Tüm subnet broadcast adreslerine gönder (Gateway için önemli)
            try {
                java.util.List<String> allBroadcastAddresses = NetworkUtils.getAllBroadcastAddresses();
                logger.debug("sendBroadcast: {} broadcast adresi bulundu: {}", 
                    allBroadcastAddresses.size(), allBroadcastAddresses);
                for (String subnetBroadcast : allBroadcastAddresses) {
                    try {
                        InetAddress broadcastAddr = InetAddress.getByName(subnetBroadcast);
                        for (int port : knownPorts) {
                            if (port == udpPort) continue; // Kendine gönderme
                            try {
                                DatagramPacket packet = new DatagramPacket(data, data.length, broadcastAddr, port);
                    socket.send(packet);
                                logger.debug("Subnet broadcast gönderildi: {} bytes -> {}:{}", data.length, subnetBroadcast, port);
                            } catch (Exception e) {
                                logger.warn("Port {} subnet broadcast hatası {}: {}", port, subnetBroadcast, e.getMessage());
                            }
                }
            } catch (Exception e) {
                        logger.debug("Subnet broadcast adresi {} işleme hatası: {}", subnetBroadcast, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.debug("Tüm subnet broadcast hatası: {}", e.getMessage());
            }
            
            // YÖNTEM 2: Global broadcast (255.255.255.255) tüm portlara gönder (fallback)
            try {
                InetAddress broadcast = InetAddress.getByName("255.255.255.255");
                for (int port : knownPorts) {
                    if (port == udpPort) continue; // Kendine gönderme
                    try {
                        DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
                    socket.send(packet);
                        logger.debug("Global broadcast gönderildi: {} bytes -> {}:{}", data.length, broadcast.getHostAddress(), port);
                    } catch (Exception e) {
                        logger.debug("Port {} global broadcast hatası: {}", port, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.debug("Global broadcast hatası: {}", e.getMessage());
            }
            
            // YÖNTEM 3: Multicast (alternatif - bazı network'ler için)
            try {
                InetAddress multicast = InetAddress.getByName("224.0.0.1");
                for (int port : knownPorts) {
                    if (port == udpPort) continue; // Kendine gönderme
                    try {
                        DatagramPacket packet = new DatagramPacket(data, data.length, multicast, port);
                        socket.send(packet);
                        logger.debug("Multicast gönderildi: {} bytes -> {}:{}", data.length, multicast.getHostAddress(), port);
                    } catch (Exception e) {
                        logger.debug("Port {} multicast hatası: {}", port, e.getMessage());
                    }
                }
            } catch (Exception e) {
                logger.debug("Multicast hatası: {}", e.getMessage());
            }
            
            // YÖNTEM 4: Bilinen peer IP'lerine doğrudan unicast (VirtualBox'ta broadcast çalışmazsa fallback)
            // Bu, broadcast'ın çalışmadığı ortamlarda peer discovery'yi sağlar
            String[] knownPeerIPs = {
                "192.168.1.10", "192.168.1.20",  // Subnet-A peer'ları
                "192.168.2.10", "192.168.2.20"   // Subnet-B peer'ları
            };
            String localIp = NetworkUtils.getLocalIPAddress();
            for (String peerIp : knownPeerIPs) {
                if (peerIp.equals(localIp)) continue; // Kendine gönderme
                try {
                    InetAddress target = InetAddress.getByName(peerIp);
                    for (int port : knownPorts) {
                        if (port == udpPort) continue;
                        try {
                            DatagramPacket packet = new DatagramPacket(data, data.length, target, port);
                            socket.send(packet);
                            logger.debug("Unicast gönderildi: {} bytes -> {}:{}", data.length, peerIp, port);
                        } catch (Exception e) {
                            // Port erişilemez, sorun değil
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Unicast hedef {} hatası: {}", peerIp, e.getMessage());
                }
            }
            
        } catch (Exception e) {
            logger.error("Broadcast gönderme hatası", e);
        }
    }
    
    /**
     * IP adresinden subnet broadcast adresini hesaplar
     * Örnek: 192.168.1.10 -> 192.168.1.255 (varsayılan /24 subnet)
     */
    private String getSubnetBroadcastAddress(String ipAddress) {
        try {
            String[] parts = ipAddress.split("\\.");
            if (parts.length == 4) {
                // /24 subnet için (en yaygın) - son octet'i 255 yap
                return parts[0] + "." + parts[1] + "." + parts[2] + ".255";
            }
        } catch (Exception e) {
            logger.debug("Subnet broadcast hesaplama hatası: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Unicast mesaj gönderir (belirli bir peer'a)
     */
    private void sendUnicast(Object message, String targetIp, int targetPort) {
        try {
            String json = MessageSerializer.toJson(message);
            byte[] data = json.getBytes();
            
            InetAddress address = InetAddress.getByName(targetIp);
            DatagramPacket packet = new DatagramPacket(data, data.length, address, targetPort);
            
            socket.send(packet);
            
        } catch (Exception e) {
            logger.error("Unicast gönderme hatası: {}:{}", targetIp, targetPort, e);
        }
    }
    
    /**
     * UDP paketlerini dinler
     */
    private void listen() {
        byte[] buffer = new byte[MAX_PACKET_SIZE];
        
        logger.info("UDP listener başlatıldı, port {} dinleniyor...", udpPort);
        
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                
                String data = new String(packet.getData(), 0, packet.getLength());
                String senderIp = packet.getAddress().getHostAddress();
                int senderPort = packet.getPort();
                
                logger.debug("UDP paketi alındı: {} bytes, gönderen: {}:{}", 
                    packet.getLength(), senderIp, senderPort);
                
                // Mesajı işle (async)
                executor.submit(() -> handleMessage(data, senderIp));
                
            } catch (SocketException e) {
                if (running) {
                    logger.error("Socket hatası", e);
                }
                break;
            } catch (Exception e) {
                logger.error("Paket alma hatası", e);
            }
        }
    }
    
    /**
     * Gelen mesajı işler
     */
    private void handleMessage(String json, String senderIp) {
        try {
            String msgType = MessageSerializer.getMessageType(json);
            if (msgType == null) {
                return;
            }
            
            logger.debug("handleMessage: msgType={}", msgType);
            
            switch (MessageType.valueOf(msgType)) {
                case DISCOVER:
                    handleDiscover(json, senderIp);
                    break;
                case DISCOVER_REPLY:
                    handleDiscoverReply(json);
                    break;
                case SEARCH:
                    logger.debug("handleMessage: SEARCH case'ine girdi");
                    handleSearch(json, senderIp);
                    break;
                case SEARCH_REPLY:
                    logger.debug("handleMessage: SEARCH_REPLY case'ine girdi");
                    handleSearchReply(json);
                    break;
                default:
                    logger.debug("handleMessage: UNKNOWN msgType={}", msgType);
            }
            
        } catch (Exception e) {
            logger.error("Mesaj işleme hatası", e);
        }
    }
    
    /**
     * DISCOVER mesajını işler (subnet-aware)
     */
    private void handleDiscover(String json, String senderIp) {
        DiscoverMessage msg = MessageSerializer.fromJson(json, DiscoverMessage.class);
        if (msg == null) {
            logger.debug("DISCOVER mesajı parse edilemedi");
            return;
        }
        
        logger.debug("DISCOVER mesajı alındı: {} (senderId={}, senderIp={}, subnet={}, bizimSubnet={}, bizGateway={})", 
            msg.getSenderName(), msg.getSenderId(), senderIp, msg.getSubnetId(), subnetId, isGatewayPeer);
        
        // Kendi mesajımızı ignore et
        if (msg.getSenderId().equals(peerId)) {
            logger.debug("DISCOVER mesajı kendi mesajımız, ignore edildi");
            return;
        }
        
        // Daha önce gördüğümüz mesaj mı?
        if (seenMessages.containsKey(msg.getMsgId())) {
            logger.debug("DISCOVER mesajı zaten görüldü: {}", msg.getMsgId());
            return;
        }
        
        // Subnet kontrolü: Farklı subnet'ten mesaj geliyorsa ve gateway değilsek ignore et
        String msgSubnetId = msg.getSubnetId();
        if (subnetId != null && msgSubnetId != null && !msgSubnetId.equals(subnetId)) {
            // Farklı subnet'ten mesaj geldi
            if (!isGatewayPeer) {
                // Gateway değiliz - farklı subnet'ten gelen mesajı ignore et (subnet izolasyonu)
                logger.debug("DISCOVER mesajı farklı subnet'ten geldi ve gateway değiliz, ignore edildi " +
                    "(from={}, to={})", msgSubnetId, subnetId);
                return;  // Mesajı işleme, subnet izolasyonu korunur
            }
            // Gateway isek devam et (mesajı işle ve forward et)
            logger.debug("DISCOVER mesajı farklı subnet'ten geldi ama gateway'iz, işleniyor (from={}, gateway={})", 
                msgSubnetId, subnetId);
        }
        
        // Mesajı görüldü olarak işaretle
        seenMessages.put(msg.getMsgId(), System.currentTimeMillis());
        
        // Peer'ı kaydet (subnet bilgisi dahil)
        PeerInfo peerInfo = msg.toPeerInfo();
        notifyPeerDiscovered(peerInfo);
        
        String senderSubnet = msgSubnetId != null ? msgSubnetId : "unknown";
        logger.info("Peer keşfedildi: {} ({}:{}, subnet={})", 
            msg.getSenderName(), msg.getSenderIp(), msg.getSenderTcpPort(), senderSubnet);
        
        // DISCOVER_REPLY gönder (unicast, subnet bilgisi dahil)
        DiscoverMessage reply = new DiscoverMessage(
            MessageType.DISCOVER_REPLY.name(),
            UUID.randomUUID().toString(),
            peerId,
            peerName,
            NetworkUtils.getLocalIPAddress(),
            udpPort,
            tcpPort,
            0,  // Reply için TTL gerekmiyor
            subnetId,
            isGatewayPeer
        );
        
        sendUnicast(reply, msg.getSenderIp(), msg.getSenderUdpPort());
        
        // TTL > 0 ise subnet-aware forward et
        if (msg.getTtl() > 0) {
            msg.setTtl(msg.getTtl() - 1);
            executor.submit(() -> {
                try {
                    Thread.sleep(100);  // Küçük gecikme
                    forwardDiscoverMessage(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
    
    /**
     * DISCOVER mesajını subnet-aware forward eder
     */
    private void forwardDiscoverMessage(DiscoverMessage msg) {
        String msgSubnetId = msg.getSubnetId();
        
        // Subnet bilgisi yoksa eski davranış
        if (msgSubnetId == null || subnetId == null) {
            sendBroadcast(msg);
            logger.debug("DISCOVER mesajı forward edildi (TTL={}, subnet bilgisi yok)", msg.getTtl());
            return;
        }
        
        // Aynı subnet içindeyse normal broadcast
        if (msgSubnetId.equals(subnetId)) {
            sendBroadcast(msg);
            sendToKnownPorts(msg);
            logger.debug("DISCOVER mesajı aynı subnet içinde forward edildi (TTL={}, subnet={})", 
                msg.getTtl(), subnetId);
            
            // ✅ DÜZELTME: Gateway ise, aynı subnet'ten gelen mesajı bağlı subnet'lere de forward et
            // ÖNEMLİ: Orijinal mesajı değiştirmemek için yeni mesaj oluştur
            if (isGatewayPeer && !connectedSubnets.isEmpty()) {
                for (String connectedSubnet : connectedSubnets) {
                    if (!connectedSubnet.equals(subnetId)) {
                        // Bağlı subnet'e forward et - yeni mesaj oluştur, orijinal mesajı değiştirme
                        DiscoverMessage forwardedMsg = new DiscoverMessage(
                            msg.getType(),
                            msg.getMsgId(),
                            msg.getSenderId(),
                            msg.getSenderName(),
                            msg.getSenderIp(),
                            msg.getSenderUdpPort(),
                            msg.getSenderTcpPort(),
                            msg.getTtl(),
                            connectedSubnet,  // Hedef subnet
                            msg.isGatewayPeer()
                        );
                        
                        // Gateway'in tüm network interface'lerinin broadcast adreslerine gönder
                        java.util.List<String> allBroadcastAddresses = NetworkUtils.getAllBroadcastAddresses();
                        int[] knownPorts = {50000, 50010, 50020, 50030, 50040, 50050, 50060, 50070, 50080, 50090};
                        String json = com.cse471.p2p.protocol.MessageSerializer.toJson(forwardedMsg);
                        if (json != null && !json.trim().isEmpty()) {
                            byte[] data = json.getBytes();
                            for (String broadcastAddr : allBroadcastAddresses) {
                                try {
                                    InetAddress broadcast = InetAddress.getByName(broadcastAddr);
                                    for (int port : knownPorts) {
                                        if (port == udpPort) continue;
                                        try {
                                            DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
                                            socket.send(packet);
                                            logger.debug("Gateway forward broadcast gönderildi: {} bytes -> {}:{} (subnet: {} -> {})", 
                                                data.length, broadcastAddr, port, msgSubnetId, connectedSubnet);
                                        } catch (Exception e) {
                                            logger.debug("Gateway forward broadcast hatası {}:{} - {}", broadcastAddr, port, e.getMessage());
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.debug("Gateway forward broadcast işleme hatası {}: {}", broadcastAddr, e.getMessage());
                                }
                            }
                        }
                        sendToKnownPortsSubnetAware(forwardedMsg);
                        logger.debug("DISCOVER mesajı gateway üzerinden bağlı subnet'e forward edildi " +
                            "(subnet değiştirildi: {} -> {}) (TTL={})", 
                            msgSubnetId, connectedSubnet, msg.getTtl());
                    }
                }
            }
        } else {
            // Farklı subnet'ten geliyor - sadece gateway peer'lar forward edebilir
            if (isGatewayPeer && peerManagerSupplier != null) {
                PeerManager peerManager = peerManagerSupplier.get();
                if (peerManager != null) {
                    // ✅ DÜZELTME: Gateway farklı subnet'ten mesaj aldığında, 
                    // mesajı gateway'in subnet'ine forward et
                    // ÖNEMLİ: Orijinal mesajı değiştirmemek için yeni mesaj oluştur
                    DiscoverMessage forwardedMsg = new DiscoverMessage(
                        msg.getType(),
                        msg.getMsgId(),
                        msg.getSenderId(),
                        msg.getSenderName(),
                        msg.getSenderIp(),
                        msg.getSenderUdpPort(),
                        msg.getSenderTcpPort(),
                        msg.getTtl(),
                        subnetId,  // Gateway'in subnet'i
                        msg.isGatewayPeer()
                    );
                    
                    // Gateway ise: Bu subnet'teki diğer peer'lara forward et - tüm broadcast adreslerine gönder
                    java.util.List<String> allBroadcastAddresses = NetworkUtils.getAllBroadcastAddresses();
                    int[] knownPorts = {50000, 50010, 50020, 50030, 50040, 50050, 50060, 50070, 50080, 50090};
                    String json = com.cse471.p2p.protocol.MessageSerializer.toJson(forwardedMsg);
                    if (json != null && !json.trim().isEmpty()) {
                        byte[] data = json.getBytes();
                        for (String broadcastAddr : allBroadcastAddresses) {
                            try {
                                InetAddress broadcast = InetAddress.getByName(broadcastAddr);
                                for (int port : knownPorts) {
                                    if (port == udpPort) continue;
                                    try {
                                        DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
                                        socket.send(packet);
                                        logger.debug("Gateway forward broadcast gönderildi: {} bytes -> {}:{} (subnet: {} -> {})", 
                                            data.length, broadcastAddr, port, msgSubnetId, subnetId);
                                    } catch (Exception e) {
                                        logger.debug("Gateway forward broadcast hatası {}:{} - {}", broadcastAddr, port, e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.debug("Gateway forward broadcast işleme hatası {}: {}", broadcastAddr, e.getMessage());
                            }
                        }
                    }
                    sendToKnownPortsSubnetAware(forwardedMsg);  // Localhost test için
                    
                    logger.debug("DISCOVER mesajı gateway üzerinden forward edildi (subnet değiştirildi: {} -> {}) " +
                        "(TTL={}, originalSubnet={}, gatewaySubnet={})", 
                        msgSubnetId, subnetId, msg.getTtl(), msgSubnetId, subnetId);
                }
            } else {
                // Gateway değiliz - farklı subnet'ten gelen mesajı forward ETMEYİZ (subnet izolasyonu)
                logger.debug("DISCOVER mesajı farklı subnet'ten geldi ama gateway değiliz, forward edilmedi " +
                    "(TTL={}, from={}, to={})", msg.getTtl(), msgSubnetId, subnetId);
                // Hiçbir şey yapma - subnet izolasyonu korunur
            }
        }
    }
    
    /**
     * DISCOVER_REPLY mesajını işler (subnet bilgisi dahil)
     */
    private void handleDiscoverReply(String json) {
        DiscoverMessage msg = MessageSerializer.fromJson(json, DiscoverMessage.class);
        if (msg == null) return;
        
        if (msg.getSenderId().equals(peerId)) {
            return;
        }
        
        PeerInfo peerInfo = msg.toPeerInfo();
        notifyPeerDiscovered(peerInfo);
        
        String senderSubnet = msg.getSubnetId() != null ? msg.getSubnetId() : "unknown";
        logger.debug("Peer reply alındı: {} ({}:{}, subnet={})", 
            msg.getSenderName(), msg.getSenderIp(), msg.getSenderTcpPort(), senderSubnet);
    }
    
    /**
     * SEARCH mesajını işler (subnet-aware forwarding ile)
     */
    private void handleSearch(String json, String senderIp) {
        logger.debug("handleSearch BAŞLADI, senderIp={}", senderIp);
        
        SearchMessage msg = MessageSerializer.fromJson(json, SearchMessage.class);
        if (msg == null) {
            logger.debug("handleSearch: msg null, parse hatası");
            return;
        }
        
        logger.debug("handleSearch: msgId={}, senderId={}, query='{}'", 
            msg.getMsgId(), msg.getSenderId(), msg.getQuery());
        
        if (msg.getSenderId().equals(peerId)) {
            logger.debug("handleSearch: Kendi mesajımız, skip (peerId={})", peerId);
            return;
        }
        
        // Daha önce gördüğümüz mesaj mı?
        if (seenMessages.containsKey(msg.getMsgId())) {
            logger.debug("handleSearch: Mesaj zaten görüldü, skip (msgId={})", msg.getMsgId());
            return;
        }
        
        // Subnet kontrolü: Farklı subnet'ten mesaj geliyorsa ve gateway değilsek ignore et
        String msgSubnetId = msg.getSubnetId();
        logger.debug("handleSearch: msgSubnetId={}, bizimSubnetId={}, isGateway={}", 
            msgSubnetId, subnetId, isGatewayPeer);
            
        if (subnetId != null && msgSubnetId != null && !msgSubnetId.equals(subnetId)) {
            // Farklı subnet'ten mesaj geldi
            if (!isGatewayPeer) {
                // Gateway değiliz - farklı subnet'ten gelen mesajı ignore et (subnet izolasyonu)
                logger.debug("handleSearch: Farklı subnet, gateway değiliz, SKIP (from={}, to={})", 
                    msgSubnetId, subnetId);
                return;  // Mesajı işleme, subnet izolasyonu korunur
            }
            // Gateway isek devam et (mesajı işle ve forward et)
            logger.debug("handleSearch: Farklı subnet AMA gateway, devam ediyoruz");
        }
        
        // Mesajı görüldü olarak işaretle
        seenMessages.put(msg.getMsgId(), System.currentTimeMillis());
        
        // Sender IP bilgisini mesaja ekle (SEARCH_REPLY göndermek için)
        // Bu bilgi, peer henüz keşfedilmemişse bile reply gönderebilmemizi sağlar
        msg.setSenderIp(senderIp);
        // Sender UDP port bilgisini de tahmin et (genellikle mesajın geldiği port)
        // NOT: Eğer mesajda senderUdpPort bilgisi yoksa, bilinen portları deneriz
        if (msg.getSenderUdpPort() == 0) {
            // Port bilgisi yok, default olarak 50000 veya 50010 dene
            // Bu bilgi aslında DiscoverMessage'dan alınmalı
        }
        
        // Search request listener'ları bilgilendir
        notifySearchRequest(msg);
        
        String senderSubnet = msgSubnetId != null ? msgSubnetId : "unknown";
        logger.info("=== SEARCH isteği alındı: query='{}', subnet={}, TTL={}, senderIp={} ===", 
            msg.getQuery(), senderSubnet, msg.getTtl(), senderIp);
        
        // TTL > 0 ise subnet-aware forward et
        if (msg.getTtl() > 0) {
            msg.setTtl(msg.getTtl() - 1);
            executor.submit(() -> {
                try {
                    Thread.sleep(100);  // Küçük gecikme
                    forwardSearchMessage(msg);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
    
    /**
     * SEARCH mesajını subnet-aware forward eder
     */
    private void forwardSearchMessage(SearchMessage msg) {
        String msgSubnetId = msg.getSubnetId();
        
        // Subnet bilgisi yoksa eski davranış
        if (msgSubnetId == null || subnetId == null) {
            sendBroadcast(msg);
            sendToKnownPorts(msg);
            logger.debug("SEARCH mesajı forward edildi (TTL={}, subnet bilgisi yok)", msg.getTtl());
            return;
        }
        
        // Aynı subnet içindeyse normal broadcast
        if (msgSubnetId.equals(subnetId)) {
            sendBroadcast(msg);
            sendToKnownPorts(msg);
            logger.debug("SEARCH mesajı aynı subnet içinde forward edildi (TTL={}, subnet={})", 
                msg.getTtl(), subnetId);
            
            // ✅ DÜZELTME: Gateway ise, aynı subnet'ten gelen mesajı bağlı subnet'lere de forward et
            // ÖNEMLİ: Orijinal mesajı değiştirmemek için yeni mesaj oluştur
            if (isGatewayPeer && !connectedSubnets.isEmpty()) {
                for (String connectedSubnet : connectedSubnets) {
                    if (!connectedSubnet.equals(subnetId)) {
                        // Bağlı subnet'e forward et - yeni mesaj oluştur, orijinal mesajı değiştirme
                        SearchMessage forwardedMsg = new SearchMessage(
                            msg.getType(),
                            msg.getMsgId(),
                            msg.getSenderId(),
                            msg.getQuery(),
                            connectedSubnet,  // Hedef subnet
                            msg.getTtl()
                        );
                        
                        // Gateway'in tüm network interface'lerinin broadcast adreslerine gönder
                        java.util.List<String> allBroadcastAddresses = NetworkUtils.getAllBroadcastAddresses();
                        int[] knownPorts = {50000, 50010, 50020, 50030, 50040, 50050, 50060, 50070, 50080, 50090};
                        String json = com.cse471.p2p.protocol.MessageSerializer.toJson(forwardedMsg);
                        if (json != null && !json.trim().isEmpty()) {
                            byte[] data = json.getBytes();
                            for (String broadcastAddr : allBroadcastAddresses) {
                                try {
                                    InetAddress broadcast = InetAddress.getByName(broadcastAddr);
                                    for (int port : knownPorts) {
                                        if (port == udpPort) continue;
                                        try {
                                            DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
                                            socket.send(packet);
                                            logger.info("Gateway SEARCH forward broadcast gönderildi: {} bytes -> {}:{} (subnet: {} -> {})", 
                                                data.length, broadcastAddr, port, msgSubnetId, connectedSubnet);
                                        } catch (Exception e) {
                                            logger.debug("Gateway SEARCH forward broadcast hatası {}:{} - {}", broadcastAddr, port, e.getMessage());
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.debug("Gateway SEARCH forward broadcast işleme hatası {}: {}", broadcastAddr, e.getMessage());
                                }
                            }
                        }
                        sendToKnownPortsSubnetAware(forwardedMsg);
                        logger.info("SEARCH mesajı gateway üzerinden bağlı subnet'e forward edildi " +
                            "(subnet değiştirildi: {} -> {}) (TTL={})", 
                            msgSubnetId, connectedSubnet, msg.getTtl());
                    }
                }
            }
        } else {
            // Farklı subnet'ten geliyor - sadece gateway peer'lar forward edebilir
            if (isGatewayPeer && peerManagerSupplier != null) {
                PeerManager peerManager = peerManagerSupplier.get();
                if (peerManager != null) {
                    // ✅ DÜZELTME: Gateway farklı subnet'ten mesaj aldığında, 
                    // mesajı gateway'in subnet'ine forward et
                    // ÖNEMLİ: Orijinal mesajı değiştirmemek için yeni mesaj oluştur
                    SearchMessage forwardedMsg = new SearchMessage(
                        msg.getType(),
                        msg.getMsgId(),
                        msg.getSenderId(),
                        msg.getQuery(),
                        subnetId,  // Gateway'in subnet'i
                        msg.getTtl()
                    );
                    
                    // Gateway ise: Bu subnet'teki diğer peer'lara forward et - tüm broadcast adreslerine gönder
                    java.util.List<String> allBroadcastAddresses = NetworkUtils.getAllBroadcastAddresses();
                    int[] knownPorts = {50000, 50010, 50020, 50030, 50040, 50050, 50060, 50070, 50080, 50090};
                    String json = com.cse471.p2p.protocol.MessageSerializer.toJson(forwardedMsg);
                    if (json != null && !json.trim().isEmpty()) {
                        byte[] data = json.getBytes();
                        for (String broadcastAddr : allBroadcastAddresses) {
                            try {
                                InetAddress broadcast = InetAddress.getByName(broadcastAddr);
                                for (int port : knownPorts) {
                                    if (port == udpPort) continue;
                                    try {
                                        DatagramPacket packet = new DatagramPacket(data, data.length, broadcast, port);
                                        socket.send(packet);
                                        logger.info("Gateway SEARCH forward broadcast gönderildi: {} bytes -> {}:{} (subnet: {} -> {})", 
                                            data.length, broadcastAddr, port, msgSubnetId, subnetId);
                                    } catch (Exception e) {
                                        logger.debug("Gateway SEARCH forward broadcast hatası {}:{} - {}", broadcastAddr, port, e.getMessage());
                                    }
                                }
                            } catch (Exception e) {
                                logger.debug("Gateway SEARCH forward broadcast işleme hatası {}: {}", broadcastAddr, e.getMessage());
                            }
                        }
                    }
                    sendToKnownPortsSubnetAware(forwardedMsg);  // Localhost test için
                    
                    logger.info("SEARCH mesajı gateway üzerinden forward edildi (subnet değiştirildi: {} -> {}) " +
                        "(TTL={}, originalSubnet={}, gatewaySubnet={})", 
                        msgSubnetId, subnetId, msg.getTtl(), msgSubnetId, subnetId);
                }
            } else {
                // Gateway değiliz - farklı subnet'ten gelen mesajı forward ETMEYİZ (subnet izolasyonu)
                logger.debug("SEARCH mesajı farklı subnet'ten geldi ama gateway değiliz, forward edilmedi " +
                    "(TTL={}, from={}, to={})", msg.getTtl(), msgSubnetId, subnetId);
                // Hiçbir şey yapma - subnet izolasyonu korunur
            }
        }
    }
    
    /**
     * SEARCH_REPLY mesajını işler (subnet-aware, gateway forwarding ile)
     */
    private void handleSearchReply(String json) {
        try {
            SearchMessage reply = MessageSerializer.fromJson(json, SearchMessage.class);
            if (reply == null) return;
            
            // Kendi mesajımızı ignore et
            if (reply.getSenderId().equals(peerId)) {
                return;
            }
            
            // ✅ DÜZELTME: Subnet kontrolü - Gateway farklı subnet'ten gelen SEARCH_REPLY mesajlarını işleyebilmeli
            String replySubnetId = reply.getSubnetId();
            if (subnetId != null && replySubnetId != null && !replySubnetId.equals(subnetId)) {
                // Farklı subnet'ten mesaj geldi
                if (!isGatewayPeer) {
                    // Gateway değiliz - farklı subnet'ten gelen SEARCH_REPLY'yi ignore et (subnet izolasyonu)
                    logger.debug("SEARCH_REPLY mesajı farklı subnet'ten geldi ve gateway değiliz, ignore edildi " +
                        "(from={}, to={})", replySubnetId, subnetId);
                    return;
                }
                // Gateway isek devam et (mesajı işle ve forward et)
                logger.debug("SEARCH_REPLY mesajı farklı subnet'ten gateway üzerinden alındı (from={}, gateway={})", 
                    replySubnetId, subnetId);
            }
            
            // ✅ ÖNEMLİ DÜZELTME: Gateway ise, SEARCH_REPLY mesajını hem kendi subnet'indeki hem de bağlı subnet'teki peer'lara forward et
            // (Subnet-B'deki peer bir SEARCH mesajı gönderdiğinde, Subnet-A'daki peer'ların SEARCH_REPLY'lerini alabilmesi için)
            // (Subnet-A'daki peer bir SEARCH mesajı gönderdiğinde, Subnet-B'deki peer'ların SEARCH_REPLY'lerini alabilmesi için)
            if (isGatewayPeer && peerManagerSupplier != null && !connectedSubnets.isEmpty()) {
                PeerManager peerManager = peerManagerSupplier.get();
                if (peerManager != null) {
                    // 1. Gateway'in kendi subnet'indeki peer'lara forward et (Subnet-B'den gelen mesajlar için)
                    if (replySubnetId != null && !replySubnetId.equals(subnetId)) {
                        // Farklı subnet'ten mesaj geldi, gateway'in subnet'indeki peer'lara forward et
                        List<PeerInfo> peersInGatewaySubnet = peerManager.getPeersInSubnet(subnetId);
                        for (PeerInfo peer : peersInGatewaySubnet) {
                            if (!peer.getPeerId().equals(peerId) && !peer.getPeerId().equals(reply.getSenderId())) {
                                SearchMessage forwardedReply = new SearchMessage(
                                    reply.getType(),
                                    reply.getMsgId(),
                                    reply.getSenderId(),
                                    reply.getQuery(),
                                    subnetId,  // Gateway'in subnet'i
                                    0
                                );
                                forwardedReply.setResults(reply.getResults());
                                sendUnicast(forwardedReply, peer.getIpAddress(), peer.getUdpPort());
                                logger.info("SEARCH_REPLY mesajı gateway üzerinden kendi subnet'ine forward edildi: {} -> {} (subnet: {} -> {})", 
                                    reply.getSenderId(), peer.getPeerName(), replySubnetId, subnetId);
                            }
                        }
                    }
                    
                    // 2. Bağlı subnet'teki tüm peer'lara forward et (Gateway'in kendi subnet'inden gelen mesajlar için)
                    for (String connectedSubnet : connectedSubnets) {
                        if (!connectedSubnet.equals(subnetId)) {
                            // Bağlı subnet'teki tüm peer'lara forward et
                            List<PeerInfo> peersInConnectedSubnet = peerManager.getPeersInSubnet(connectedSubnet);
                            for (PeerInfo peer : peersInConnectedSubnet) {
                                if (!peer.getPeerId().equals(peerId) && !peer.getPeerId().equals(reply.getSenderId())) {
                                    // Forward edilen mesajın subnetId'sini hedef subnet yap
                                    SearchMessage forwardedReply = new SearchMessage(
                                        reply.getType(),
                                        reply.getMsgId(),
                                        reply.getSenderId(),
                                        reply.getQuery(),
                                        connectedSubnet,  // Hedef subnet
                                        0
                                    );
                                    forwardedReply.setResults(reply.getResults());
                                    sendUnicast(forwardedReply, peer.getIpAddress(), peer.getUdpPort());
                                    logger.info("SEARCH_REPLY mesajı gateway üzerinden bağlı subnet'e forward edildi: {} -> {} (subnet: {} -> {})", 
                                        reply.getSenderId(), peer.getPeerName(), 
                                        replySubnetId != null ? replySubnetId : subnetId, connectedSubnet);
                                }
                            }
                        }
                    }
                }
            }
            
            logger.info("=== SEARCH_REPLY alındı: {} video (subnet={}) ===", 
                reply.getResults() != null ? reply.getResults().size() : 0, replySubnetId);
            notifySearchReply(reply);
        } catch (Exception e) {
            logger.error("SEARCH_REPLY parse hatası", e);
        }
    }
    
    /**
     * SEARCH_REPLY gönderir (subnet bilgisi dahil, gateway forwarding ile)
     */
    public void sendSearchReply(SearchMessage request, List<VideoInfo> results, String targetIp, int targetPort) {
        SearchMessage reply = new SearchMessage(
            MessageType.SEARCH_REPLY.name(),
            request.getMsgId(),
            peerId,
            null,
            subnetId,  // ✅ DÜZELTME: Subnet bilgisi eklendi
            0  // REPLY için TTL gerekmiyor
        );
        reply.setResults(results);
        
        // Orijinal istek sahibine gönder
        sendUnicast(reply, targetIp, targetPort);
        logger.debug("SEARCH_REPLY gönderildi: {} sonuç (subnet={})", results.size(), subnetId);
        
        // ✅ ÖNEMLİ DÜZELTME: Gateway ise, SEARCH_REPLY mesajını bağlı subnet'teki tüm peer'lara da gönder
        // (Gateway'in kendi videolarını bağlı subnet'teki peer'ların görebilmesi için)
        if (isGatewayPeer && peerManagerSupplier != null && !connectedSubnets.isEmpty()) {
            PeerManager peerManager = peerManagerSupplier.get();
            if (peerManager != null) {
                for (String connectedSubnet : connectedSubnets) {
                    if (!connectedSubnet.equals(subnetId)) {
                        // Bağlı subnet'teki tüm peer'lara gönder
                        List<PeerInfo> peersInConnectedSubnet = peerManager.getPeersInSubnet(connectedSubnet);
                        for (PeerInfo peer : peersInConnectedSubnet) {
                            if (!peer.getPeerId().equals(peerId)) {
                                // Forward edilen mesajın subnetId'sini hedef subnet yap
                                SearchMessage forwardedReply = new SearchMessage(
                                    reply.getType(),
                                    reply.getMsgId(),
                                    reply.getSenderId(),
                                    reply.getQuery(),
                                    connectedSubnet,  // Hedef subnet
                                    0
                                );
                                forwardedReply.setResults(reply.getResults());
                                sendUnicast(forwardedReply, peer.getIpAddress(), peer.getUdpPort());
                                logger.info("SEARCH_REPLY mesajı gateway üzerinden bağlı subnet'e gönderildi: {} -> {} (subnet: {} -> {})", 
                                    peerName, peer.getPeerName(), subnetId, connectedSubnet);
                            }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * Eski mesajları temizleme görevi
     */
    private void cleanupTask() {
        while (running) {
            try {
                Thread.sleep(10000);  // Her 10 saniyede bir
                
                long now = System.currentTimeMillis();
                seenMessages.entrySet().removeIf(entry -> 
                    now - entry.getValue() > MESSAGE_EXPIRY_MS
                );
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }
    
    // Event listener registration
    public void addPeerDiscoveredListener(Consumer<PeerInfo> listener) {
        peerDiscoveredListeners.add(listener);
    }
    
    public void addSearchRequestListener(Consumer<SearchMessage> listener) {
        searchRequestListeners.add(listener);
    }
    
    public void addSearchReplyListener(Consumer<SearchMessage> listener) {
        searchReplyListeners.add(listener);
    }
    
    private void notifyPeerDiscovered(PeerInfo peerInfo) {
        for (Consumer<PeerInfo> listener : peerDiscoveredListeners) {
            executor.submit(() -> listener.accept(peerInfo));
        }
    }
    
    private void notifySearchRequest(SearchMessage message) {
        for (Consumer<SearchMessage> listener : searchRequestListeners) {
            executor.submit(() -> listener.accept(message));
        }
    }
    
    private void notifySearchReply(SearchMessage message) {
        for (Consumer<SearchMessage> listener : searchReplyListeners) {
            executor.submit(() -> listener.accept(message));
        }
    }
    
    public boolean isRunning() {
        return running;
    }
}

