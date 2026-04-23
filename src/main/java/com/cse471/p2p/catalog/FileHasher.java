package com.cse471.p2p.catalog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hash hesaplama utility
 */
public class FileHasher {
    private static final Logger logger = LoggerFactory.getLogger(FileHasher.class);
    private static final int BUFFER_SIZE = 8192;
    
    /**
     * Dosyanın SHA-256 hash'ini hesaplar (streaming - büyük dosyalar için uygun)
     * @param filePath Hash'lenecek dosya
     * @return Hex formatında hash string
     */
    public static String computeSHA256(Path filePath) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            
            try (InputStream fis = Files.newInputStream(filePath)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                
                while ((bytesRead = fis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            
            byte[] hashBytes = digest.digest();
            return bytesToHex(hashBytes);
            
        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algoritması bulunamadı", e);
            throw new IOException("SHA-256 desteklenmiyor", e);
        }
    }
    
    /**
     * Byte array'i hex string'e çevirir
     */
    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Dosyanın hash hesaplama için uygun olup olmadığını kontrol eder
     */
    public static boolean isValidForHashing(Path filePath) {
        try {
            return Files.isRegularFile(filePath) && 
                   Files.isReadable(filePath) && 
                   Files.size(filePath) > 0;
        } catch (IOException e) {
            return false;
        }
    }
}

