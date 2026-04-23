package com.cse471.p2p.protocol;

/**
 * TCP chunk transfer protokol sabitleri
 */
public class ChunkProtocol {
    
    // Magic number for protocol identification
    // CSE471 -> C5E471 (valid hex)
    public static final int MAGIC = 0xC5E47101;
    
    // Chunk boyutu: 256 KB
    public static final int CHUNK_SIZE = 262144;
    
    // Frame tipleri
    public static final byte CHUNK_REQUEST = 1;
    public static final byte CHUNK_RESPONSE = 2;
    public static final byte CATALOG_REQUEST = 3;
    public static final byte CATALOG_RESPONSE = 4;
    
    // Status kodları
    public static final int STATUS_OK = 0;
    public static final int STATUS_NOT_FOUND = 1;
    public static final int STATUS_ERROR = 2;
    public static final int STATUS_INVALID_CHUNK = 3;
    
    // Timeout değerleri (ms)
    public static final int CONNECTION_TIMEOUT = 5000;
    public static final int READ_TIMEOUT = 10000;
    
    private ChunkProtocol() {
        // Utility class
    }
}

