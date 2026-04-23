package com.cse471.p2p.net;

import com.cse471.p2p.protocol.ChunkProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * TCP tabanlı chunk client
 * Diğer peer'lardan chunk indirir
 */
public class ChunkClient {
    private static final Logger logger = LoggerFactory.getLogger(ChunkClient.class);
    
    /**
     * Chunk response sınıfı
     */
    public static class ChunkResponse {
        private final int status;
        private final int chunkIndex;
        private final byte[] data;
        
        public ChunkResponse(int status, int chunkIndex, byte[] data) {
            this.status = status;
            this.chunkIndex = chunkIndex;
            this.data = data;
        }
        
        public int getStatus() {
            return status;
        }
        
        public int getChunkIndex() {
            return chunkIndex;
        }
        
        public byte[] getData() {
            return data;
        }
        
        public boolean isSuccess() {
            return status == ChunkProtocol.STATUS_OK;
        }
    }
    
    /**
     * Belirli bir peer'dan chunk indirir
     * 
     * @param peerIp Peer IP adresi
     * @param peerTcpPort Peer TCP port
     * @param fileHash Video file hash
     * @param chunkIndex İstenilen chunk index
     * @return ChunkResponse
     * @throws IOException Bağlantı veya okuma hatası
     */
    public static ChunkResponse requestChunk(String peerIp, int peerTcpPort, 
                                            String fileHash, int chunkIndex) throws IOException {
        
        logger.debug("Chunk isteği: {}:{} - hash={}, chunk={}", 
            peerIp, peerTcpPort, fileHash.substring(0, 8), chunkIndex);
        
        Socket socket = null;
        try {
            // Bağlan
            socket = new Socket();
            socket.connect(new InetSocketAddress(peerIp, peerTcpPort), ChunkProtocol.CONNECTION_TIMEOUT);
            socket.setSoTimeout(ChunkProtocol.READ_TIMEOUT);
            
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            DataInputStream in = new DataInputStream(socket.getInputStream());
            
            // Request gönder
            sendChunkRequest(out, fileHash, chunkIndex);
            
            // Response al
            ChunkResponse response = receiveChunkResponse(in);
            
            if (response.isSuccess()) {
                logger.debug("Chunk alındı: {} bytes", response.getData().length);
            } else {
                logger.warn("Chunk isteği başarısız: status={}", response.getStatus());
            }
            
            return response;
            
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }
    }
    
    /**
     * Chunk request frame'i gönderir
     * Request frame:
     * int magic
     * byte type (CHUNK_REQUEST)
     * int fileHashLen
     * bytes fileHashHexUtf8
     * int chunkIndex
     */
    private static void sendChunkRequest(DataOutputStream out, String fileHash, int chunkIndex) throws IOException {
        byte[] hashBytes = fileHash.getBytes();
        
        out.writeInt(ChunkProtocol.MAGIC);
        out.writeByte(ChunkProtocol.CHUNK_REQUEST);
        out.writeInt(hashBytes.length);
        out.write(hashBytes);
        out.writeInt(chunkIndex);
        out.flush();
    }
    
    /**
     * Chunk response frame'i alır
     * Response frame:
     * int magic
     * byte type (CHUNK_RESPONSE)
     * int status
     * int chunkIndex
     * int payloadLen
     * bytes payload
     */
    private static ChunkResponse receiveChunkResponse(DataInputStream in) throws IOException {
        int magic = in.readInt();
        if (magic != ChunkProtocol.MAGIC) {
            throw new IOException("Geçersiz magic number: 0x" + Integer.toHexString(magic));
        }
        
        byte type = in.readByte();
        if (type != ChunkProtocol.CHUNK_RESPONSE) {
            throw new IOException("Beklenmeyen response tipi: " + type);
        }
        
        int status = in.readInt();
        int chunkIndex = in.readInt();
        int payloadLen = in.readInt();
        
        byte[] payload = new byte[payloadLen];
        if (payloadLen > 0) {
            in.readFully(payload);
        }
        
        return new ChunkResponse(status, chunkIndex, payload);
    }
    
    /**
     * Bağlantı testi yapar
     */
    public static boolean testConnection(String peerIp, int peerTcpPort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(peerIp, peerTcpPort), 2000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}

