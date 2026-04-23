package com.cse471.p2p.stream;

import com.cse471.p2p.net.ChunkClient;
import com.cse471.p2p.protocol.ChunkProtocol;
import com.cse471.p2p.protocol.PeerInfo;
import com.cse471.p2p.protocol.VideoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bir video için multi-source download session
 */
public class DownloadSession {
    private static final Logger logger = LoggerFactory.getLogger(DownloadSession.class);
    
    private static final int MAX_CONCURRENT_DOWNLOADS = 4;
    private static final int MAX_RETRY_COUNT = 3;
    private static final int BUFFERING_THRESHOLD_CHUNKS = 20;  // İlk 20 chunk
    
    // ✅ TEST MODE: İndirmeyi yavaşlatmak için (test için true yap)
    private static final boolean TEST_MODE = true;  // Production için false
    private static final int TEST_DELAY_MS = 150;  // Her chunk'ta 150ms bekle (ayarlanabilir: 50-200ms)
    
    public enum Status {
        INITIALIZING,
        BUFFERING,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        FAILED
    }
    
    private final String sessionId;
    private final VideoInfo videoInfo;
    private final Path outputFile;
    private final List<PeerInfo> availablePeers;
    
    private final BitSet receivedChunks;
    private final Queue<Integer> pendingChunks;
    private final Map<Integer, Integer> retryCount;  // chunkIndex -> retry count
    
    private final ExecutorService executor;
    private final AtomicInteger activeDownloads;
    private final AtomicInteger peerIndex;  // Round-robin peer seçimi için
    
    private volatile Status status;
    private volatile boolean stopped;
    private RandomAccessFile raf;
    
    // Progress tracking
    private long downloadedBytes;
    private long totalBytes;
    
    // Callback
    private Runnable onBufferingComplete;
    private Runnable onDownloadComplete;
    
    public DownloadSession(String sessionId, VideoInfo videoInfo, Path outputFile, List<PeerInfo> peers) {
        this.sessionId = sessionId;
        this.videoInfo = videoInfo;
        this.outputFile = outputFile;
        this.availablePeers = new CopyOnWriteArrayList<>(peers);
        
        this.receivedChunks = new BitSet(videoInfo.getChunkCount());
        this.pendingChunks = new ConcurrentLinkedQueue<>();
        this.retryCount = new ConcurrentHashMap<>();
        
        this.executor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
        this.activeDownloads = new AtomicInteger(0);
        this.peerIndex = new AtomicInteger(0);  // Round-robin başlangıcı
        
        this.status = Status.INITIALIZING;
        this.stopped = false;
        
        this.totalBytes = videoInfo.getSizeBytes();
        this.downloadedBytes = 0;
        
        // Pending chunk queue'yu doldur
        for (int i = 0; i < videoInfo.getChunkCount(); i++) {
            pendingChunks.add(i);
        }
    }
    
    /**
     * Download'u başlatır
     */
    public void start() throws IOException {
        logger.info("Download session başlatılıyor: {}", videoInfo.getDisplayName());
        
        // Output dosyasını hazırla
        Files.createDirectories(outputFile.getParent());
        raf = new RandomAccessFile(outputFile.toFile(), "rw");
        raf.setLength(totalBytes);  // Dosya boyutunu ayarla
        
        status = Status.BUFFERING;
        
        // Download worker'ları başlat
        for (int i = 0; i < MAX_CONCURRENT_DOWNLOADS; i++) {
            executor.submit(this::downloadWorker);
        }
        
        // ✅ MULTI-SOURCE LOG - Kaç peer'dan indirileceği görünsün
        logger.info("🚀 Download başladı: {} chunk, {} MB, {} PEER'dan paralel indirme", 
            videoInfo.getChunkCount(), totalBytes / 1024 / 1024, availablePeers.size());
        for (PeerInfo peer : availablePeers) {
            logger.info("   📡 Kaynak Peer: {} [{}:{}]", peer.getPeerName(), peer.getIpAddress(), peer.getTcpPort());
        }
    }
    
    /**
     * Download worker thread
     */
    private void downloadWorker() {
        while (!stopped && !pendingChunks.isEmpty()) {
            Integer chunkIndex = pendingChunks.poll();
            if (chunkIndex == null) {
                break;
            }
            
            // Zaten alınmış mı kontrol et (deduplication)
            if (receivedChunks.get(chunkIndex)) {
                continue;
            }
            
            activeDownloads.incrementAndGet();
            
            try {
                boolean success = downloadChunk(chunkIndex);
                
                if (!success) {
                    // Retry logic
                    int retries = retryCount.getOrDefault(chunkIndex, 0);
                    if (retries < MAX_RETRY_COUNT) {
                        retryCount.put(chunkIndex, retries + 1);
                        pendingChunks.add(chunkIndex);  // Tekrar kuyruğa ekle
                        logger.debug("Chunk {} retry edilecek ({}/{})", chunkIndex, retries + 1, MAX_RETRY_COUNT);
                    } else {
                        logger.error("Chunk {} maksimum retry sayısına ulaştı", chunkIndex);
                    }
                }
                
            } catch (Exception e) {
                logger.error("Chunk {} download hatası", chunkIndex, e);
            } finally {
                activeDownloads.decrementAndGet();
            }
            
            // Status güncellemesi
            updateStatus();
        }
    }
    
    /**
     * Belirli bir chunk'ı indirir - Round-robin peer seçimi ile
     */
    private boolean downloadChunk(int chunkIndex) {
        if (availablePeers.isEmpty()) {
            logger.warn("Chunk {} için peer yok!", chunkIndex);
            return false;
        }
        
        // ROUND-ROBIN PEER SEÇİMİ - Her chunk farklı peer'dan alınabilir
        int startIndex = peerIndex.getAndIncrement() % availablePeers.size();
        int peerCount = availablePeers.size();
        
        for (int i = 0; i < peerCount; i++) {
            int currentIndex = (startIndex + i) % peerCount;
            PeerInfo peer = availablePeers.get(currentIndex);
            
            try {
                ChunkClient.ChunkResponse response = ChunkClient.requestChunk(
                    peer.getIpAddress(),
                    peer.getTcpPort(),
                    videoInfo.getFileHash(),
                    chunkIndex
                );
                
                if (response.isSuccess()) {
                    // Chunk'ı dosyaya yaz
                    writeChunk(chunkIndex, response.getData());
                    
                    // ✅ TEST MODE: İndirmeyi yavaşlat (test için)
                    if (TEST_MODE) {
                        try {
                            Thread.sleep(TEST_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    
                    synchronized (receivedChunks) {
                        if (!receivedChunks.get(chunkIndex)) {
                            receivedChunks.set(chunkIndex);
                            downloadedBytes += response.getData().length;
                        }
                    }
                    
                    // ✅ CHUNK KAYNAĞI LOG'U - Hangi peer'dan geldiği net görünsün
                    logger.info("📦 Chunk {} <- {} [{}:{}]", 
                        chunkIndex, peer.getPeerName(), peer.getIpAddress(), peer.getTcpPort());
                    
                    return true;
                }
                
            } catch (IOException e) {
                logger.debug("Chunk {} hatası ({}:{}): {}", 
                    chunkIndex, peer.getIpAddress(), peer.getTcpPort(), e.getMessage());
            }
        }
        
        return false;
    }
    
    /**
     * Chunk'ı dosyaya yazar (random access)
     */
    private synchronized void writeChunk(int chunkIndex, byte[] data) throws IOException {
        long offset = (long) chunkIndex * ChunkProtocol.CHUNK_SIZE;
        raf.seek(offset);
        raf.write(data);
    }
    
    /**
     * Status günceller ve callback'leri tetikler
     */
    private void updateStatus() {
        int receivedCount = receivedChunks.cardinality();
        int totalCount = videoInfo.getChunkCount();
        double progress = (double) receivedCount / totalCount * 100.0;
        
        // ✅ Progress log'ları (her %10'da veya önemli milestone'larda)
        int progressPercent = (int) progress;
        boolean isMilestone = (receivedCount == 1 || 
                              receivedCount == BUFFERING_THRESHOLD_CHUNKS ||
                              (progressPercent % 10 == 0 && receivedCount > 0));
        
        // Her %10'da bir log yaz (ama aynı %10'da tekrar yazma)
        if (isMilestone) {
            logger.info("📊 Download Progress: {}/{} chunks ({}%) - Status: {}", 
                receivedCount, totalCount, String.format("%.1f", progress), status);
        }
        
        // Buffering complete kontrolü
        if (status == Status.BUFFERING && receivedCount >= BUFFERING_THRESHOLD_CHUNKS) {
            if (checkBufferingThreshold()) {
                status = Status.DOWNLOADING;
                logger.info("🎬 Buffering tamamlandı, playback başlayabilir (ilk {} chunk hazır, {}%)", 
                    BUFFERING_THRESHOLD_CHUNKS, String.format("%.1f", progress));
                
                if (onBufferingComplete != null) {
                    executor.submit(onBufferingComplete);
                }
            } else {
                logger.debug("Buffering devam ediyor: {}/{} chunk, ilk 20 henüz sıralı değil", 
                    receivedCount, BUFFERING_THRESHOLD_CHUNKS);
            }
        }
        
        // Download complete kontrolü
        if (receivedCount == totalCount) {
            Status previousStatus = status;
            status = Status.COMPLETED;
            logger.info("Download tamamlandı: {} ({}% progress)", videoInfo.getDisplayName(), getProgress());
            
            closeFile();
            
            // Eğer buffering callback hiç çağrılmadıysa (çok hızlı indiyse veya BUFFERING skip edildiyse), şimdi çağır
            if (onBufferingComplete != null && previousStatus != Status.DOWNLOADING) {
                logger.info("Download complete oldu ama buffering callback çağrılmamıştı, şimdi çağrılıyor (previousStatus={})", previousStatus);
                executor.submit(onBufferingComplete);
            }
            
            if (onDownloadComplete != null) {
                executor.submit(onDownloadComplete);
            }
        }
    }
    
    /**
     * Buffering için yeterli chunk var mı kontrol eder (private helper)
     */
    private boolean checkBufferingThreshold() {
        // İlk N chunk'ın contiguous olarak gelip gelmediğini kontrol et
        for (int i = 0; i < BUFFERING_THRESHOLD_CHUNKS && i < videoInfo.getChunkCount(); i++) {
            if (!receivedChunks.get(i)) {
                return false;
            }
        }
        return true;
    }
    
    /**
     * Download'u durdurur
     */
    public void stop() {
        logger.info("Download session durduruluyor: {}", sessionId);
        stopped = true;
        status = Status.PAUSED;
        
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        closeFile();
    }
    
    /**
     * Dosyayı kapatır
     */
    private synchronized void closeFile() {
        if (raf != null) {
            try {
                raf.close();
            } catch (IOException e) {
                logger.error("RAF kapatma hatası", e);
            }
            raf = null;
        }
    }
    
    /**
     * İndirme yüzdesi
     */
    public double getProgress() {
        return (double) receivedChunks.cardinality() / videoInfo.getChunkCount() * 100.0;
    }
    
    /**
     * İndirilen byte sayısı
     */
    public long getDownloadedBytes() {
        return downloadedBytes;
    }
    
    /**
     * İndirme hızı (tahmini, MB/s)
     */
    public String getDownloadSpeed() {
        // TODO: Gerçek hız hesabı için zaman tracking eklenebilir
        return "N/A";
    }
    
    // Getters
    public String getSessionId() {
        return sessionId;
    }
    
    public VideoInfo getVideoInfo() {
        return videoInfo;
    }
    
    public Path getOutputFile() {
        return outputFile;
    }
    
    public Status getStatus() {
        return status;
    }
    
    public boolean isStopped() {
        return stopped;
    }
    
    public boolean isBufferingComplete() {
        return status != Status.INITIALIZING && status != Status.BUFFERING;
    }
    
    public boolean isDownloadComplete() {
        return status == Status.COMPLETED;
    }
    
    /**
     * Mevcut peer'ların IP adreslerini döndürür
     */
    public List<String> getPeerIPs() {
        List<String> ips = new ArrayList<>();
        for (PeerInfo peer : availablePeers) {
            ips.add(peer.getIpAddress());
        }
        return ips;
    }
    
    /**
     * Peer sayısını döndürür
     */
    public int getPeerCount() {
        return availablePeers.size();
    }
    
    // Callbacks
    public void setOnBufferingComplete(Runnable callback) {
        this.onBufferingComplete = callback;
    }
    
    public void setOnDownloadComplete(Runnable callback) {
        this.onDownloadComplete = callback;
    }
}

