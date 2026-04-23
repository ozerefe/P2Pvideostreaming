package com.cse471.p2p.net;

import com.cse471.p2p.catalog.VideoCatalog;
import com.cse471.p2p.catalog.VideoFile;
import com.cse471.p2p.protocol.ChunkProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TCP tabanlı chunk server
 * Diğer peer'lara video chunk'ları sağlar
 */
public class ChunkServer {
    private static final Logger logger = LoggerFactory.getLogger(ChunkServer.class);
    
    private final int tcpPort;
    private final VideoCatalog catalog;
    
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private ExecutorService executor;
    private volatile boolean running;
    
    public ChunkServer(int tcpPort, VideoCatalog catalog) {
        this.tcpPort = tcpPort;
        this.catalog = catalog;
        this.executor = Executors.newCachedThreadPool();
    }
    
    /**
     * Chunk server'ı başlatır
     */
    public void start() throws IOException {
        if (running) {
            logger.warn("Chunk server zaten çalışıyor");
            return;
        }
        
        serverSocket = new ServerSocket(tcpPort);
        serverSocket.setSoTimeout(1000);  // Accept timeout
        running = true;
        
        acceptThread = new Thread(this::acceptConnections, "TCP-ChunkServer-Accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        
        logger.info("Chunk server başlatıldı: TCP port {}", tcpPort);
    }
    
    /**
     * Chunk server'ı durdurur
     */
    public void stop() {
        running = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("ServerSocket kapatma hatası", e);
        }
        
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        
        executor.shutdown();
        
        logger.info("Chunk server durduruldu");
    }
    
    /**
     * Gelen bağlantıları kabul eder
     */
    private void acceptConnections() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executor.submit(() -> handleClient(clientSocket));
                
            } catch (SocketTimeoutException e) {
                // Normal timeout, devam et
            } catch (IOException e) {
                if (running) {
                    logger.error("Bağlantı kabul hatası", e);
                }
            }
        }
    }
    
    /**
     * Client isteğini işler
     */
    private void handleClient(Socket socket) {
        try {
            socket.setSoTimeout(ChunkProtocol.READ_TIMEOUT);
            
            try (DataInputStream in = new DataInputStream(socket.getInputStream());
                 DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {
                
               
                int magic = in.readInt();
                if (magic != ChunkProtocol.MAGIC) {
                    logger.warn("Geçersiz magic number: 0x{}", Integer.toHexString(magic));
                    return;
                }
                
                // Request tipini oku
                byte requestType = in.readByte();
                
                switch (requestType) {
                    case ChunkProtocol.CHUNK_REQUEST:
                        handleChunkRequest(in, out);
                        break;
                    case ChunkProtocol.CATALOG_REQUEST:
                        handleCatalogRequest(out);
                        break;
                    default:
                        logger.warn("Bilinmeyen request tipi: {}", requestType);
                }
            }
            
        } catch (Exception e) {
            logger.error("Client işleme hatası", e);
        } finally {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * Chunk request'i işler
     * Exclusion filter kontrolü yapılır - stream request kabul etmeden önce
     */
    private void handleChunkRequest(DataInputStream in, DataOutputStream out) throws IOException {

        
        int hashLen = in.readInt();
        byte[] hashBytes = new byte[hashLen];
        in.readFully(hashBytes);
        String fileHash = new String(hashBytes);
        
        int chunkIndex = in.readInt();
        
        logger.debug("Chunk isteği: hash={}, chunk={}", fileHash.substring(0, 8), chunkIndex);
        
        // Video dosyasını bul
        VideoFile video = catalog.getVideoByHash(fileHash);
        
        if (video == null) {
            sendChunkResponse(out, chunkIndex, ChunkProtocol.STATUS_NOT_FOUND, new byte[0]);
            logger.warn("Video bulunamadı: {}", fileHash.substring(0, 8));
            return;
        }
        
        // Exclusion filter kontrolü - stream request kabul etmeden önce
        if (catalog.isExcluded(video)) {
            sendChunkResponse(out, chunkIndex, ChunkProtocol.STATUS_NOT_FOUND, new byte[0]);
            logger.warn("Chunk request reddedildi: Video exclusion filter'larına takıldı - {} (hash: {})", 
                video.getDisplayName(), fileHash.substring(0, 8));
            return;
        }
        
        if (chunkIndex < 0 || chunkIndex >= video.getChunkCount()) {
            sendChunkResponse(out, chunkIndex, ChunkProtocol.STATUS_INVALID_CHUNK, new byte[0]);
            logger.warn("Geçersiz chunk index: {}", chunkIndex);
            return;
        }
        
        // Chunk'ı oku
        try {
            byte[] chunkData = readChunk(video, chunkIndex);
            sendChunkResponse(out, chunkIndex, ChunkProtocol.STATUS_OK, chunkData);
            logger.debug("Chunk gönderildi: {} bytes", chunkData.length);
            
        } catch (IOException e) {
            sendChunkResponse(out, chunkIndex, ChunkProtocol.STATUS_ERROR, new byte[0]);
            logger.error("Chunk okuma hatası", e);
        }
    }
    
    /**
     * Catalog request'i işler
     */
    private void handleCatalogRequest(DataOutputStream out) throws IOException {
        // TODO: İleride implement edilebilir
        logger.debug("Catalog request alındı (şimdilik desteklenmiyor)");
    }
    
    /**
     * Video dosyasından belirli bir chunk'ı okur
     */
    private byte[] readChunk(VideoFile video, int chunkIndex) throws IOException {
        long offset = (long) chunkIndex * ChunkProtocol.CHUNK_SIZE;
        int chunkSize = ChunkProtocol.CHUNK_SIZE;
        
        // Son chunk daha küçük olabilir
        long remainingBytes = video.getSizeBytes() - offset;
        if (remainingBytes < chunkSize) {
            chunkSize = (int) remainingBytes;
        }
        
        byte[] buffer = new byte[chunkSize];
        
        try (RandomAccessFile raf = new RandomAccessFile(video.getFilePath().toFile(), "r")) {
            raf.seek(offset);
            int bytesRead = raf.read(buffer);
            
            if (bytesRead != chunkSize) {
                throw new IOException("Chunk tam okunamadı: beklenen=" + chunkSize + ", okunan=" + bytesRead);
            }
        }
        
        return buffer;
    }
    
    /**
     * Chunk response gönderir
     * Response frame:
     * int magic
     * byte type (CHUNK_RESPONSE)
     * int status
     * int chunkIndex
     * int payloadLen
     * bytes payload
     */
    private void sendChunkResponse(DataOutputStream out, int chunkIndex, int status, byte[] payload) throws IOException {
        out.writeInt(ChunkProtocol.MAGIC);
        out.writeByte(ChunkProtocol.CHUNK_RESPONSE);
        out.writeInt(status);
        out.writeInt(chunkIndex);
        out.writeInt(payload.length);
        if (payload.length > 0) {
            out.write(payload);
        }
        out.flush();
    }
    
    public boolean isRunning() {
        return running;
    }
}

