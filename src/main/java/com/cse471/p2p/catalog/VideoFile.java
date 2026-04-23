package com.cse471.p2p.catalog;

import com.cse471.p2p.protocol.ChunkProtocol;
import com.cse471.p2p.protocol.VideoInfo;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Yerel video dosyası bilgisi (catalog için)
 */
public class VideoFile {
    private String displayName;
    private Path filePath;
    private String fileHash;      // SHA-256 hex
    private long sizeBytes;
    private int chunkCount;
    private String extension;
    
    public VideoFile(String displayName, Path filePath, String fileHash, long sizeBytes, String extension) {
        this.displayName = displayName;
        this.filePath = filePath;
        this.fileHash = fileHash;
        this.sizeBytes = sizeBytes;
        this.extension = extension;
        this.chunkCount = (int) Math.ceil((double) sizeBytes / ChunkProtocol.CHUNK_SIZE);
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public Path getFilePath() {
        return filePath;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public long getSizeBytes() {
        return sizeBytes;
    }
    
    public int getChunkCount() {
        return chunkCount;
    }
    
    public String getExtension() {
        return extension;
    }
    
    /**
     * VideoInfo'ya dönüştür (network paylaşımı için)
     */
    public VideoInfo toVideoInfo() {
        return new VideoInfo(displayName, fileHash, sizeBytes, chunkCount, extension);
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoFile videoFile = (VideoFile) o;
        return Objects.equals(fileHash, videoFile.fileHash);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fileHash);
    }
    
    @Override
    public String toString() {
        return "VideoFile{" +
                "displayName='" + displayName + '\'' +
                ", fileHash='" + fileHash.substring(0, 8) + "...' " +
                ", size=" + formatSize(sizeBytes) +
                '}';
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}

