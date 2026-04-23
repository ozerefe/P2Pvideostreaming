package com.cse471.p2p.protocol;

import java.util.Objects;

/**
 * Video metadata bilgisi
 */
public class VideoInfo {
    private String displayName;
    private String fileHash;      // SHA-256 hex
    private long sizeBytes;
    private int chunkCount;
    private String extension;     // .mp4, .mkv vb.
    
    public VideoInfo() {
    }
    
    public VideoInfo(String displayName, String fileHash, long sizeBytes, int chunkCount, String extension) {
        this.displayName = displayName;
        this.fileHash = fileHash;
        this.sizeBytes = sizeBytes;
        this.chunkCount = chunkCount;
        this.extension = extension;
    }
    
    public String getDisplayName() {
        return displayName;
    }
    
    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
    
    public String getFileHash() {
        return fileHash;
    }
    
    public void setFileHash(String fileHash) {
        this.fileHash = fileHash;
    }
    
    public long getSizeBytes() {
        return sizeBytes;
    }
    
    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }
    
    public int getChunkCount() {
        return chunkCount;
    }
    
    public void setChunkCount(int chunkCount) {
        this.chunkCount = chunkCount;
    }
    
    public String getExtension() {
        return extension;
    }
    
    public void setExtension(String extension) {
        this.extension = extension;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        VideoInfo videoInfo = (VideoInfo) o;
        return Objects.equals(fileHash, videoInfo.fileHash);
    }
    
    @Override
    public int hashCode() {
        return Objects.hash(fileHash);
    }
    
    @Override
    public String toString() {
        return displayName + " (" + formatSize(sizeBytes) + ")";
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        char pre = "KMGTPE".charAt(exp - 1);
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }
}

