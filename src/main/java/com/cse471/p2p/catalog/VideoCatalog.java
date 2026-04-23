package com.cse471.p2p.catalog;

import com.cse471.p2p.protocol.VideoInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Yerel video catalog yönetimi
 * Exclusion filters ile dosya/klasör/uzantı filtreleme desteği
 */
public class VideoCatalog {
    private static final Logger logger = LoggerFactory.getLogger(VideoCatalog.class);
    
    // Desteklenen video formatları
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(
        ".mp4", ".mkv", ".avi", ".mov", ".flv", ".wmv", ".webm"
    );
    
    private final Map<String, VideoFile> catalog;  // fileHash -> VideoFile
    private Path rootFolder;
    
    // ✅ EXCLUSION FILTERS - Dosya/klasör/uzantı filtreleme
    private Set<String> excludedFilenameKeywords = new HashSet<>();  // Dosya adında geçen yasaklı anahtar kelimeler (örn: "test", "backup")
    private Set<String> excludedFolderPaths = new HashSet<>();        // Hariç tutulan klasör yolları (tam path veya klasör adı)
    private Set<String> excludedExtensions = new HashSet<>();        // Hariç tutulan dosya uzantıları (örn: ".avi", ".flv")
    
    // Local'de filtrelenen video hash'lerini sakla (network'ten gelen aynı videoları da filtrelemek için)
    private Set<String> excludedVideoHashes = new HashSet<>();
    
    public VideoCatalog() {
        this.catalog = new ConcurrentHashMap<>();
    }
    
    /**
     * Root folder'ı ayarlar ve tüm videoları tarar
     */
    public void setRootFolder(Path rootFolder) throws IOException {
        if (!Files.isDirectory(rootFolder)) {
            throw new IOException("Root folder geçerli bir dizin değil: " + rootFolder);
        }
        
        this.rootFolder = rootFolder;
        scanFolder();
    }
    
    /**
     * Root folder'ı tarar ve catalog'u günceller
     * Exclusion filters uygular
     */
    public synchronized void scanFolder() throws IOException {
        if (rootFolder == null) {
            logger.warn("Root folder ayarlanmamış, tarama yapılamıyor");
            return;
        }
        
        logger.info("Video taraması başlıyor: {}", rootFolder);
        logger.info("Exclusion filters: {} filename keywords, {} folder paths, {} extensions",
            excludedFilenameKeywords.size(), excludedFolderPaths.size(), 
            excludedExtensions.size());
        
        catalog.clear();
        excludedVideoHashes.clear();  // Yeni tarama başlamadan önce temizle
        int excludedCount = 0;
        
        try (Stream<Path> paths = Files.walk(rootFolder)) {
            List<Path> allVideoFiles = paths
                .filter(Files::isRegularFile)
                .filter(this::isSupportedVideoFile)
                .collect(Collectors.toList());
            
            logger.info("{} video dosyası bulundu (filtreleme öncesi)", allVideoFiles.size());
            
            for (Path videoPath : allVideoFiles) {
                // ✅ EXCLUSION FILTER KONTROLÜ
                if (shouldExclude(videoPath)) {
                    logger.debug("Excluded: {}", videoPath.getFileName());
                    excludedCount++;
                    
                    // Filtrelenen video'nun hash'ini kaydet (network'ten gelen aynı videoyu da filtrelemek için)
                    try {
                        String fileHash = FileHasher.computeSHA256(videoPath);
                        excludedVideoHashes.add(fileHash);
                        logger.debug("Excluded video hash saved: {} ({})", 
                            fileHash.substring(0, 8), videoPath.getFileName());
                    } catch (Exception e) {
                        logger.warn("Could not compute hash for excluded file: {}", videoPath, e);
                    }
                    
                    continue;
                }
                
                try {
                    processVideoFile(videoPath);
                } catch (Exception e) {
                    logger.error("Video işlenemedi: {}", videoPath, e);
                }
            }
        }
        
        logger.info("Catalog taraması tamamlandı. {} video dahil, {} video hariç tutuldu", 
            catalog.size(), excludedCount);
    }
    
    /**
     * Dosyanın exclusion filter'larına uyup uymadığını kontrol eder
     * Merkezi filtreleme fonksiyonu - tüm exclusion kontrolleri burada yapılır
     * 
     * @param filePath Video dosyasının path'i
     * @return true if file should be excluded, false otherwise
     */
    public boolean isExcluded(Path filePath) {
        if (filePath == null) {
            return false;
        }
        
        String fileName = filePath.getFileName().toString();
        String fileNameLower = fileName.toLowerCase();
        String extension = getFileExtension(fileName).toLowerCase();
        
        // 1. Filename keyword exclusion filter
        // Dosya adında yasaklı anahtar kelimelerden biri geçiyorsa hariç tut
        for (String keyword : excludedFilenameKeywords) {
            if (fileNameLower.contains(keyword.toLowerCase())) {
                logger.info("File excluded by filename keyword: '{}' (keyword: '{}', file: '{}')", 
                    fileName, keyword, filePath);
                return true;
            }
        }
        
        // 2. Extension exclusion filter
        // Uzantı yasaklı listede ise hariç tut
        if (excludedExtensions.contains(extension)) {
            logger.info("File excluded by extension: '{}' (extension: '{}', file: '{}')", 
                fileName, extension, filePath);
            return true;
        }
        
        // 3. Folder path exclusion filter
        // Dosya, hariç tutulan klasör yollarından birinin içindeyse hariç tut
        String filePathStr = filePath.toString().replace("\\", "/").toLowerCase();
        for (String excludedFolder : excludedFolderPaths) {
            String excludedFolderNormalized = excludedFolder.replace("\\", "/").toLowerCase();
            
            // Tam path kontrolü: dosya path'i excluded folder path'i içeriyorsa hariç tut
            // Örnek: "/videos/private" -> "/videos/private/test.mp4" hariç tutulur
            // Örnek: "private" -> "/videos/private/test.mp4" veya "/other/private/video.mp4" hariç tutulur
            if (filePathStr.contains(excludedFolderNormalized)) {
                // Path'in başında veya "/" ile ayrılmış bir klasör adı olarak geçiyorsa hariç tut
                if (filePathStr.startsWith(excludedFolderNormalized + "/") ||
                    filePathStr.contains("/" + excludedFolderNormalized + "/") ||
                    filePathStr.endsWith("/" + excludedFolderNormalized)) {
                    logger.info("File excluded by folder path: '{}' (folder: '{}', file: '{}')", 
                        fileName, excludedFolder, filePath);
                    return true;
                }
            }
        }
        
        return false;
    }
    
    /**
     * VideoFile objesi için exclusion kontrolü
     * @param videoFile VideoFile objesi
     * @return true if video should be excluded, false otherwise
     */
    public boolean isExcluded(VideoFile videoFile) {
        if (videoFile == null) {
            return false;
        }
        return isExcluded(videoFile.getFilePath());
    }
    
    /**
     * VideoInfo objesi için exclusion kontrolü
     * Hem local catalog'daki hem de network'ten gelen videolar için çalışır
     * @param videoInfo VideoInfo objesi
     * @return true if video should be excluded, false otherwise
     */
    public boolean isExcluded(com.cse471.p2p.protocol.VideoInfo videoInfo) {
        if (videoInfo == null) {
            return false;
        }
        
        // Önce catalog'da var mı kontrol et (local video)
        VideoFile videoFile = catalog.get(videoInfo.getFileHash());
        if (videoFile != null) {
            return isExcluded(videoFile);
        }
        
        // Eğer bu video local'de filtrelenmişse (excludedVideoHashes'de varsa), 
        // network'ten de gelse filtrelenmeli
        if (excludedVideoHashes.contains(videoInfo.getFileHash())) {
            logger.info("Network video excluded: Local'de filtrelenmiş video (hash: {}, name: {})", 
                videoInfo.getFileHash().substring(0, 8), videoInfo.getDisplayName());
            return true;
        }
        
        // Network'ten gelen video için VideoInfo'dan bilgileri kullan
        // VideoInfo'da sadece displayName ve extension var, path yok
        // Bu yüzden sadece filename keyword ve extension kontrolü yapabiliriz
        String displayName = videoInfo.getDisplayName();
        if (displayName == null || displayName.isEmpty()) {
            return false;
        }
        
        String fileNameLower = displayName.toLowerCase();
        String extension = videoInfo.getExtension() != null ? 
            videoInfo.getExtension().toLowerCase() : 
            getFileExtension(displayName).toLowerCase();
        
        // 1. Filename keyword exclusion filter
        for (String keyword : excludedFilenameKeywords) {
            if (fileNameLower.contains(keyword.toLowerCase())) {
                logger.info("Network video excluded by filename keyword: '{}' (keyword: '{}')", 
                    displayName, keyword);
                return true;
            }
        }
        
        // 2. Extension exclusion filter
        if (excludedExtensions.contains(extension)) {
            logger.info("Network video excluded by extension: '{}' (extension: '{}')", 
                displayName, extension);
            return true;
        }
        
        // Not: Folder path kontrolü network videoları için yapılamaz çünkü path bilgisi yok
        // Folder path kontrolü sadece local videolar için çalışır
        
        return false;
    }
    
    /**
     * Eski metod - geriye uyumluluk için (private)
     */
    private boolean shouldExclude(Path filePath) {
        return isExcluded(filePath);
    }
    
    /**
     * Bir video dosyasını işler ve catalog'a ekler
     */
    private void processVideoFile(Path videoPath) throws IOException {
        String fileName = videoPath.getFileName().toString();
        String extension = getFileExtension(fileName);
        long sizeBytes = Files.size(videoPath);
        
        logger.debug("Hash hesaplanıyor: {}", fileName);
        String fileHash = FileHasher.computeSHA256(videoPath);
        
        VideoFile videoFile = new VideoFile(
            fileName,
            videoPath,
            fileHash,
            sizeBytes,
            extension
        );
        
        catalog.put(fileHash, videoFile);
        logger.info("Video eklendi: {} (hash: {})", fileName, fileHash.substring(0, 8));
    }
    
    /**
     * Dosya uzantısını kontrol eder
     */
    private boolean isSupportedVideoFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        return SUPPORTED_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
    
    /**
     * Dosya uzantısını döndürür
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0) {
            return fileName.substring(lastDot);
        }
        return ".mp4"; // default
    }
    
    /**
     * Anahtar kelimeye göre video arar
     * Exclusion filter'lar uygulanır
     */
    public List<VideoInfo> search(String query) {
        List<VideoInfo> results;
        if (query == null || query.trim().isEmpty()) {
            results = getAllVideos();
        } else {
            String lowerQuery = query.toLowerCase();
            results = catalog.values().stream()
                .filter(vf -> vf.getDisplayName().toLowerCase().contains(lowerQuery))
                .map(VideoFile::toVideoInfo)
                .collect(Collectors.toList());
        }
        
        // Exclusion filter'ları uygula
        return results.stream()
            .filter(video -> !isExcluded(video))
            .collect(Collectors.toList());
    }
    
    /**
     * Tüm videoları döndürür
     * Exclusion filter'lar uygulanır
     */
    public List<VideoInfo> getAllVideos() {
        return catalog.values().stream()
            .filter(vf -> !isExcluded(vf))
            .map(VideoFile::toVideoInfo)
            .collect(Collectors.toList());
    }
    
    /**
     * Hash'e göre video dosyası bulur
     */
    public VideoFile getVideoByHash(String fileHash) {
        return catalog.get(fileHash);
    }
    
    /**
     * Catalog'da video var mı?
     */
    public boolean hasVideo(String fileHash) {
        return catalog.containsKey(fileHash);
    }
    
    /**
     * Catalog boyutu
     */
    public int size() {
        return catalog.size();
    }
    
    /**
     * Catalog'u temizler
     */
    public void clear() {
        catalog.clear();
        rootFolder = null;
    }
    
    public Path getRootFolder() {
        return rootFolder;
    }
    
    // ==================== EXCLUSION FILTER METHODS ====================
    
    /**
     * Dosya adında geçen yasaklı anahtar kelime ekler (örn: "test", "backup")
     * Dosya adında bu kelimelerden biri geçiyorsa hariç tutulur
     */
    public void addExcludedFilenameKeyword(String keyword) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            excludedFilenameKeywords.add(keyword.trim());
            logger.info("Exclusion filter added (filename keyword): '{}'", keyword);
        }
    }
    
    /**
     * Hariç tutulan klasör yolu ekler (tam path veya klasör adı)
     * Örnek: "/videos/private" veya "private"
     */
    public void addExcludedFolderPath(String folderPath) {
        if (folderPath != null && !folderPath.trim().isEmpty()) {
            excludedFolderPaths.add(folderPath.trim());
            logger.info("Exclusion filter added (folder path): '{}'", folderPath);
        }
    }
    
    /**
     * Hariç tutulan dosya uzantısı ekler (örn: ".avi", ".flv" veya "avi", "flv")
     */
    public void addExcludedExtension(String extension) {
        if (extension != null && !extension.trim().isEmpty()) {
            String ext = extension.trim();
            if (!ext.startsWith(".")) {
                ext = "." + ext;
            }
            excludedExtensions.add(ext.toLowerCase());
            logger.info("Exclusion filter added (extension): '{}'", ext);
        }
    }
    
    /**
     * Tüm exclusion filter'ları tek seferde ayarlar
     */
    public void setExclusionFilters(Set<String> filenameKeywords, Set<String> folderPaths, 
                                    Set<String> extensions) {
        this.excludedFilenameKeywords = filenameKeywords != null ? new HashSet<>(filenameKeywords) : new HashSet<>();
        this.excludedFolderPaths = folderPaths != null ? new HashSet<>(folderPaths) : new HashSet<>();
        this.excludedExtensions = extensions != null ? new HashSet<>(extensions) : new HashSet<>();
        
        logger.info("Exclusion filters updated: {} filename keywords, {} folder paths, {} extensions",
            excludedFilenameKeywords.size(), excludedFolderPaths.size(), excludedExtensions.size());
    }
    
    /**
     * Tüm exclusion filter'ları temizler
     */
    public void clearExclusionFilters() {
        excludedFilenameKeywords.clear();
        excludedFolderPaths.clear();
        excludedExtensions.clear();
        excludedVideoHashes.clear();  // Filtrelenen video hash'lerini de temizle
        logger.info("All exclusion filters cleared");
    }
    
    /**
     * Toplam exclusion filter sayısını döndürür
     */
    public int getExclusionFilterCount() {
        return excludedFilenameKeywords.size() + excludedFolderPaths.size() + excludedExtensions.size();
    }
    
    /**
     * Text formatında exclusion filter'ları parse eder ve ayarlar
     * Format: Her satır bir filter
     * - "keyword" -> filename keyword (dosya adında geçen anahtar kelime)
     * - "folder:/path/to/folder" veya "folder:foldername" -> folder path
     * - ".ext" veya "ext" -> extension
     * - "#" ile başlayan satırlar yorum olarak kabul edilir
     */
    public void parseAndSetFilters(String filterText) {
        excludedFilenameKeywords.clear();
        excludedFolderPaths.clear();
        excludedExtensions.clear();
        
        if (filterText == null || filterText.trim().isEmpty()) {
            return;
        }
        
        String[] lines = filterText.split("\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue; // Boş satır veya yorum
            }
            
            if (trimmed.toLowerCase().startsWith("folder:")) {
                String folderPath = trimmed.substring(7).trim();
                addExcludedFolderPath(folderPath);
            } else if (trimmed.startsWith(".") || (!trimmed.contains("/") && !trimmed.contains("\\") && trimmed.length() <= 5)) {
                // Uzantı gibi görünüyor (.ext veya ext)
                addExcludedExtension(trimmed);
            } else {
                // Diğer durumlarda filename keyword olarak kabul et
                addExcludedFilenameKeyword(trimmed);
            }
        }
    }
    
    /**
     * Mevcut exclusion filter'ları text formatında döndürür
     */
    public String getFiltersAsText() {
        StringBuilder sb = new StringBuilder();
        
        for (String keyword : excludedFilenameKeywords) {
            sb.append(keyword).append("\n");
        }
        for (String folder : excludedFolderPaths) {
            sb.append("folder:").append(folder).append("\n");
        }
        for (String ext : excludedExtensions) {
            sb.append(ext).append("\n");
        }
        
        return sb.toString();
    }
    
    // Getters for exclusion filters
    public Set<String> getExcludedFilenameKeywords() { 
        return new HashSet<>(excludedFilenameKeywords); 
    }
    
    public Set<String> getExcludedFolderPaths() { 
        return new HashSet<>(excludedFolderPaths); 
    }
    
    public Set<String> getExcludedExtensions() { 
        return new HashSet<>(excludedExtensions); 
    }
}



