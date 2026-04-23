package com.cse471.p2p.app;

import com.cse471.p2p.protocol.VideoInfo;
import com.cse471.p2p.stream.DownloadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.List;
import java.util.*;
import java.util.Set;
import java.util.HashSet;

/**
 * Swing ana pencere - JavaFX'den dönüştürülmüş
 */
public class MainFrame extends JFrame implements UICallback {
    private static final Logger logger = LoggerFactory.getLogger(MainFrame.class);
    
    private final P2PController controller;
    
    // UI Components
    private JTextField searchField;
    private JButton searchButton;
    private JButton streamButton;
    private DefaultListModel<VideoInfo> videoListModel;
    private JList<VideoInfo> availableVideosList;
    private JTable activeStreamsTable;
    private DefaultTableModel streamTableModel;
    private JTextArea eventLogArea;
    private JProgressBar globalBufferBar;
    private JLabel statusLabel;
    private JLabel peerCountLabel;  // Peer sayısı göstergesi
    private JLabel rootFolderLabel;   // Root Video Folder göstergesi
    private JLabel bufferFolderLabel; // Buffer Folder göstergesi
    
    // Menu items (for enabling/disabling)
    private JMenuItem connectItem;
    private JMenuItem disconnectItem;
    
    // VLCJ Player (Callback mode - macOS uyumlu)
    private MediaPlayerFactory mediaPlayerFactory;
    private EmbeddedMediaPlayer mediaPlayer;
    private VideoSurfacePanel videoSurfacePanel;  // Custom panel for video rendering
    
    
    private JPanel playerPanel;
    private String currentVideoPath;  // İndirilmiş videonun path'i (Play butonuna basınca oynatılacak)
    
    // Full screen için
    private JFrame fullScreenFrame = null;
    private boolean isFullScreen = false;
    
    // Background timer for UI updates
    private javax.swing.Timer uiUpdateTimer;
    
    // Tamamlanan indirmeler (kalıcı - oynatmak için seçilebilir)
    private final Map<String, CompletedDownload> completedDownloads = new LinkedHashMap<>();
    
    // Otomatik oynatma denenen session'lar (tekrar denememek için)
    private final Set<String> autoPlayAttempted = new HashSet<>();
    
    // Tamamlanan indirme bilgisi
    private static class CompletedDownload {
        String videoName;
        String peerIp;
        String filePath;
        int chunkCount;
        long completedTime;
        
        CompletedDownload(String name, String ip, String path, int chunks) {
            this.videoName = name;
            this.peerIp = ip;
            this.filePath = path;
            this.chunkCount = chunks;
            this.completedTime = System.currentTimeMillis();
        }
    }
    
    public MainFrame(P2PController controller) {
        this.controller = controller;
        initUI();
        startUIUpdateTimer();
    }
    
    /**
     * UI'yi başlatır
     */
    private void initUI() {
        setTitle("P2P Video Streaming - " + controller.getPeerName());
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 800);
        setLocationRelativeTo(null);
        
        // Menu bar
        setJMenuBar(createMenuBar());
        
        // Main layout
        setLayout(new BorderLayout(10, 10));
        
        // Top panel: Search
        add(createSearchPanel(), BorderLayout.NORTH);
        
        // Center: Split between left (videos) and right (player + streams)
        JSplitPane mainSplitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        mainSplitPane.setLeftComponent(createLeftPanel());
        mainSplitPane.setRightComponent(createRightPanel());
        mainSplitPane.setDividerLocation(350);
        add(mainSplitPane, BorderLayout.CENTER);
        
        // Bottom: Event log + status
        add(createBottomPanel(), BorderLayout.SOUTH);
        
        // Window close handler
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                handleClose();
            }
        });
        
        logger.info("Swing UI oluşturuldu");
    }
    
    /**
     * Menu bar oluşturur
     */
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();
        
        // Stream menu
        JMenu streamMenu = new JMenu("Stream");
        
        connectItem = new JMenuItem("Connect");
        connectItem.addActionListener(e -> handleConnect());
        
        disconnectItem = new JMenuItem("Disconnect");
        disconnectItem.setEnabled(false); // Başlangıçta disabled
        disconnectItem.addActionListener(e -> handleDisconnect());
        
        JMenuItem setRootFolderItem = new JMenuItem("Set Root Video Folder");
        setRootFolderItem.addActionListener(e -> handleSetRootFolder());
        
        JMenuItem setBufferFolderItem = new JMenuItem("Set Buffer Folder");
        setBufferFolderItem.addActionListener(e -> handleSetBufferFolder());
        
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> handleClose());
        
        streamMenu.add(connectItem);
        streamMenu.add(disconnectItem);
        streamMenu.addSeparator();
        streamMenu.add(setRootFolderItem);
        streamMenu.add(setBufferFolderItem);
        streamMenu.addSeparator();
        
        // ✅ BONUS: Exclusion Filters Menu Item
        JMenuItem exclusionFiltersItem = new JMenuItem("Exclusion Filters...");
        exclusionFiltersItem.addActionListener(e -> handleExclusionFilters());
        streamMenu.add(exclusionFiltersItem);
        
        streamMenu.addSeparator();
        streamMenu.add(exitItem);
        
        // Help menu
        JMenu helpMenu = new JMenu("Help");
        
        JMenuItem aboutItem = new JMenuItem("About Developer");
        aboutItem.addActionListener(e -> showAboutDialog());
        
        helpMenu.add(aboutItem);
        
        menuBar.add(streamMenu);
        menuBar.add(helpMenu);
        
        return menuBar;
    }
    
    /**
     * Arama paneli
     */
    private JPanel createSearchPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        panel.setBorder(BorderFactory.createTitledBorder("Search Videos"));
        
        searchField = new JTextField(30);
        searchField.addActionListener(e -> handleSearch());
        
        searchButton = new JButton("Search");
        searchButton.setEnabled(false); // Başlangıçta disabled (connect gerekli)
        searchButton.addActionListener(e -> handleSearch());
        
        panel.add(new JLabel("Query:"));
        panel.add(searchField);
        panel.add(searchButton);
        
        return panel;
    }
    
    /**
     * Sol panel: Available Videos
     */
    private JPanel createLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Available Videos on Network"));
        
        // Video listesi
        videoListModel = new DefaultListModel<>();
        availableVideosList = new JList<>(videoListModel);
        availableVideosList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        availableVideosList.setCellRenderer(new VideoListCellRenderer());
        
        JScrollPane scrollPane = new JScrollPane(availableVideosList);
        panel.add(scrollPane, BorderLayout.CENTER);
        
        // Stream butonu
        streamButton = new JButton("Stream Selected Video");
        streamButton.setEnabled(false);
        streamButton.addActionListener(e -> handleStreamVideo());
        
        availableVideosList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                streamButton.setEnabled(availableVideosList.getSelectedValue() != null);
            }
        });
        
        panel.add(streamButton, BorderLayout.SOUTH);
        
        return panel;
    }
    
    /**
     * Sağ panel: Player + Active Streams
     */
    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(2, 2)); // Daha az boşluk
        
        // Üst: VLCJ Player + Kontroller - daha kompakt
        JPanel playerContainer = new JPanel(new BorderLayout());
        playerContainer.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(60, 60, 60), 1), // İnce border
            BorderFactory.createEmptyBorder(2, 2, 2, 2) // Minimal padding
        ));
        playerContainer.setBackground(Color.BLACK);
        
        try {
            // 1. MediaPlayerFactory oluştur (VLC options ile)
            mediaPlayerFactory = new MediaPlayerFactory(
                "--no-video-title-show",
                "--avcodec-hw=none",  // macOS uyumluluk için HW decode kapat
                "--verbose=2"
            );
            
            // 2. EmbeddedMediaPlayer oluştur
            mediaPlayer = mediaPlayerFactory.mediaPlayers().newEmbeddedMediaPlayer();
            
            // 3. Custom video surface panel oluştur
            videoSurfacePanel = new VideoSurfacePanel();
            videoSurfacePanel.setPreferredSize(new Dimension(550, 320)); // Biraz küçültüldü
            videoSurfacePanel.setBackground(Color.BLACK);
            
            // 4. CallbackVideoSurface oluştur ve bağla
            CallbackVideoSurface videoSurface = new CallbackVideoSurface(
                videoSurfacePanel.getBufferFormatCallback(),
                videoSurfacePanel.getRenderCallback(),
                true,
                VideoSurfaceAdapters.getVideoSurfaceAdapter()
            );
            mediaPlayer.videoSurface().set(videoSurface);
            
            // 5. Panel'i container'a ekle - sıfır padding
            playerPanel = new JPanel(new BorderLayout());
            playerPanel.add(videoSurfacePanel, BorderLayout.CENTER);
            playerPanel.setBackground(Color.BLACK);
            
            // Video kontrol paneli (kompakt)
            JPanel controlPanel = createVideoControlPanel();
            
            playerContainer.add(playerPanel, BorderLayout.CENTER);
            playerContainer.add(controlPanel, BorderLayout.SOUTH);
            
            logger.info("VLCJ player başarıyla oluşturuldu (Callback mode - macOS uyumlu!)");
        } catch (Exception e) {
            logger.error("VLCJ başlatma hatası - VLC kurulu değil olabilir", e);
            JLabel errorLabel = new JLabel("VLC Player Not Available. Please install VLC Media Player.", SwingConstants.CENTER);
            errorLabel.setForeground(Color.RED);
            playerContainer.add(errorLabel, BorderLayout.CENTER);
        }
        
        panel.add(playerContainer, BorderLayout.CENTER);
        
        // Alt: Active Streams Table
        JPanel streamsPanel = new JPanel(new BorderLayout());
        streamsPanel.setBorder(BorderFactory.createTitledBorder("Active Streams"));
        
        String[] columnNames = {"Video Name", "Source Peer IP", "Chunks", "Progress %", "Status"};
        streamTableModel = new DefaultTableModel(columnNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
        
        activeStreamsTable = new JTable(streamTableModel);
        activeStreamsTable.setFillsViewportHeight(true);
        activeStreamsTable.setSelectionMode(javax.swing.ListSelectionModel.SINGLE_SELECTION);
        activeStreamsTable.setToolTipText("Double-click to play video");
        
        // Çift tıklama ile video oynat
        activeStreamsTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = activeStreamsTable.getSelectedRow();
                    if (row >= 0) {
                        String videoName = (String) streamTableModel.getValueAt(row, 0);
                        
                        // Tamamlanmış videoyu oynat (completedDownloads'dan bul)
                        CompletedDownload found = null;
                        for (CompletedDownload cd : completedDownloads.values()) {
                            if (cd.videoName.equals(videoName)) {
                                found = cd;
                                break;
                            }
                        }
                        
                        if (found != null) {
                            playVideo(found.filePath, found.videoName);
                        } else {
                            // Henüz indirilmemiş - indirme başlat
                            onLog("⚠ Video henüz indirilmedi: " + videoName);
                        }
                    }
                }
            }
        });
        
        JScrollPane tableScrollPane = new JScrollPane(activeStreamsTable);
        tableScrollPane.setPreferredSize(new Dimension(600, 120)); // Biraz küçültüldü
        streamsPanel.add(tableScrollPane, BorderLayout.CENTER);
        
        panel.add(streamsPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    // Video yüklenmiş mi flag'i
    private boolean videoLoaded = false;
    
    /**
     * Kompakt video kontrol paneli (Play/Pause, Seek, Volume)
     */
    private JPanel createVideoControlPanel() {
        // Ana kontrol paneli - dark theme, daha kompakt
        JPanel mainPanel = new JPanel();
        mainPanel.setLayout(new BoxLayout(mainPanel, BoxLayout.Y_AXIS));
        mainPanel.setBackground(new Color(28, 28, 30));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8)); // Küçültüldü
        
        // === Seek Bar (İlerleme çubuğu) - daha ince ===
        JPanel seekPanel = new JPanel(new BorderLayout(5, 0));
        seekPanel.setBackground(new Color(28, 28, 30));
        seekPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 2, 0));
        
        JSlider seekBar = new JSlider(0, 1000, 0);
        seekBar.setBackground(new Color(28, 28, 30));
        seekBar.setFocusable(false);
        seekBar.setPreferredSize(new Dimension(seekBar.getPreferredSize().width, 15)); // Daha ince
        seekBar.addMouseListener(new java.awt.event.MouseAdapter() {
            private boolean wasPlaying = false;
            
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (mediaPlayer != null && videoLoaded) {
                    wasPlaying = mediaPlayer.status().isPlaying();
                    if (wasPlaying) {
                        mediaPlayer.controls().pause();
                    }
                }
            }
            
            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (mediaPlayer != null && videoLoaded) {
                    float position = seekBar.getValue() / 1000.0f;
                    mediaPlayer.controls().setPosition(position);
                    if (wasPlaying) {
                        mediaPlayer.controls().play();
                    }
                }
            }
        });
        seekPanel.add(seekBar, BorderLayout.CENTER);
        
        // === Kontrol butonları paneli - daha kompakt ===
        JPanel controlPanel = new JPanel(new BorderLayout());
        controlPanel.setBackground(new Color(28, 28, 30));
        
        // Sol: Zaman göstergesi
        JLabel timeLabel = new JLabel("00:00 / 00:00");
        timeLabel.setForeground(new Color(180, 180, 180));
        timeLabel.setFont(new Font("SansSerif", Font.PLAIN, 11)); // Küçük font
        
        // Orta: Play/Pause butonu - daha küçük butonlar
        JPanel centerControls = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0)); // Daha az boşluk
        centerControls.setBackground(new Color(28, 28, 30));
        
        // Modern Play/Pause butonu - küçültüldü
        JButton playPauseBtn = createModernButton("▶", new Color(76, 175, 80), 32); // 45->32
        playPauseBtn.setToolTipText("Play/Pause");
        
        // 10 saniye geri butonu - küçültüldü
        JButton rewindBtn = createModernButton("⏪", new Color(80, 80, 80), 26); // 35->26
        rewindBtn.setToolTipText("10 saniye geri");
        rewindBtn.addActionListener(e -> {
            if (mediaPlayer != null && videoLoaded) {
                long currentTime = mediaPlayer.status().time();
                mediaPlayer.controls().setTime(Math.max(0, currentTime - 10000));
            }
        });
        
        // 10 saniye ileri butonu - küçültüldü
        JButton forwardBtn = createModernButton("⏩", new Color(80, 80, 80), 26); // 35->26
        forwardBtn.setToolTipText("10 saniye ileri");
        forwardBtn.addActionListener(e -> {
            if (mediaPlayer != null && videoLoaded) {
                long currentTime = mediaPlayer.status().time();
                long totalTime = mediaPlayer.status().length();
                mediaPlayer.controls().setTime(Math.min(totalTime, currentTime + 10000));
            }
        });
        
        // Play/Pause logic
        playPauseBtn.addActionListener(e -> {
            if (mediaPlayer != null) {
                if (!videoLoaded && currentVideoPath != null && !currentVideoPath.isEmpty()) {
                    logger.info("Video yükleniyor: {}", currentVideoPath);
                    mediaPlayer.media().play(currentVideoPath);
                    videoLoaded = true;
                    playPauseBtn.setText("⏸");
                    playPauseBtn.setBackground(new Color(255, 152, 0));
                } else if (videoLoaded) {
                    if (mediaPlayer.status().isPlaying()) {
                        mediaPlayer.controls().pause();
                        playPauseBtn.setText("▶");
                        playPauseBtn.setBackground(new Color(76, 175, 80));
                    } else {
                        mediaPlayer.controls().play();
                        playPauseBtn.setText("⏸");
                        playPauseBtn.setBackground(new Color(255, 152, 0));
                    }
                }
            }
        });
        
        centerControls.add(rewindBtn);
        centerControls.add(playPauseBtn);
        centerControls.add(forwardBtn);
        
        // Sağ: Volume kontrolü - daha kompakt
        JPanel volumePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
        volumePanel.setBackground(new Color(28, 28, 30));
        
        JLabel volumeIcon = new JLabel("🔊");
        volumeIcon.setFont(new Font("Dialog", Font.PLAIN, 12)); // Küçük ikon
        volumeIcon.setForeground(Color.WHITE);
        
        JSlider volumeSlider = new JSlider(0, 100, 50);
        volumeSlider.setPreferredSize(new Dimension(70, 15)); // Daha küçük
        volumeSlider.setBackground(new Color(28, 28, 30));
        volumeSlider.setFocusable(false);
        volumeSlider.addChangeListener(e -> {
            if (mediaPlayer != null) {
                int volume = volumeSlider.getValue();
                mediaPlayer.audio().setVolume(volume);
                if (volume == 0) volumeIcon.setText("🔇");
                else if (volume < 50) volumeIcon.setText("🔉");
                else volumeIcon.setText("🔊");
            }
        });
        
        // Mute butonu
        volumeIcon.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        final int[] lastVolume = {50};
        volumeIcon.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (volumeSlider.getValue() > 0) {
                    lastVolume[0] = volumeSlider.getValue();
                    volumeSlider.setValue(0);
                } else {
                    volumeSlider.setValue(lastVolume[0]);
                }
            }
        });
        
        volumePanel.add(volumeIcon);
        volumePanel.add(volumeSlider);
        
        // Full Screen butonu
        JButton fullScreenBtn = createModernButton("⛶", new Color(80, 80, 80), 26);
        fullScreenBtn.setToolTipText("Full Screen (F)");
        fullScreenBtn.addActionListener(e -> toggleFullScreen());
        volumePanel.add(fullScreenBtn);
        
        controlPanel.add(timeLabel, BorderLayout.WEST);
        controlPanel.add(centerControls, BorderLayout.CENTER);
        controlPanel.add(volumePanel, BorderLayout.EAST);
        
        // Timer ile UI güncelle
        javax.swing.Timer uiTimer = new javax.swing.Timer(500, e -> {
            if (mediaPlayer != null && videoLoaded) {
                try {
                    long currentTime = mediaPlayer.status().time();
                    long totalTime = mediaPlayer.status().length();
                    
                    // ✅ DÜZELTME: Geçersiz zaman değerlerini kontrol et
                    // VLCJ bazen -1 veya 0 döndürebilir (video henüz yüklenmemişse)
                    if (currentTime < 0) currentTime = 0;
                    if (totalTime <= 0) {
                        // Toplam süre bilinmiyor (streaming sırasında olabilir)
                        timeLabel.setText(formatTime(currentTime) + " / ??:??");
                        // Seek bar'ı güncelleme (toplam süre bilinmiyor)
                    } else {
                        // ✅ DÜZELTME: currentTime totalTime'dan büyükse sınırla
                        if (currentTime > totalTime) {
                            currentTime = totalTime;
                        }
                        
                        timeLabel.setText(formatTime(currentTime) + " / " + formatTime(totalTime));
                        
                        // Seek bar güncelle
                        if (!seekBar.getValueIsAdjusting()) {
                            int position = (int) ((currentTime * 1000) / totalTime);
                            // Position'ı 0-1000 arasında sınırla
                            position = Math.max(0, Math.min(1000, position));
                            seekBar.setValue(position);
                        }
                    }
                    
                    if (mediaPlayer.status().isPlaying()) {
                        if (playPauseBtn.getText().equals("▶")) {
                            playPauseBtn.setText("⏸");
                            playPauseBtn.setBackground(new Color(255, 152, 0));
                        }
                    } else {
                        if (playPauseBtn.getText().equals("⏸")) {
                            playPauseBtn.setText("▶");
                            playPauseBtn.setBackground(new Color(76, 175, 80));
                        }
                    }
                } catch (Exception ex) {
                    // Hata durumunda zaman gösterimini sıfırla
                    timeLabel.setText("00:00 / 00:00");
                    logger.debug("Video zaman güncelleme hatası: {}", ex.getMessage());
                }
            }
        });
        uiTimer.start();
        
        mainPanel.add(seekPanel);
        mainPanel.add(controlPanel);
        
        return mainPanel;
    }
    
    /**
     * Modern yuvarlak buton oluşturur
     */
    private JButton createModernButton(String text, Color bgColor, int size) {
        JButton button = new JButton(text) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                
                if (getModel().isRollover()) {
                    g2.setColor(bgColor.brighter());
                } else {
                    g2.setColor(bgColor);
                }
                
                g2.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Dialog", Font.BOLD, size / 3 + 2)); // Daha küçük font
                FontMetrics fm = g2.getFontMetrics();
                int textX = (getWidth() - fm.stringWidth(getText())) / 2;
                int textY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(getText(), textX, textY);
                
                g2.dispose();
            }
            
            @Override
            protected void paintBorder(Graphics g) {
                // No border
            }
        };
        
        button.setPreferredSize(new Dimension(size, size));
        button.setMinimumSize(new Dimension(size, size));
        button.setMaximumSize(new Dimension(size, size));
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.setBackground(bgColor);
        
        return button;
    }
    
    /**
     * Video oynat (tablodaki tamamlanmış videoları oynatmak için)
     */
    private void playVideo(String filePath, String videoName) {
        if (filePath == null || filePath.isEmpty()) {
            onLog("⚠ Video dosyası bulunamadı");
            return;
        }
        
        // Önceki video varsa durdur
        if (mediaPlayer != null && videoLoaded) {
            mediaPlayer.controls().stop();
        }
        
        currentVideoPath = filePath;
        videoLoaded = false;
        
        // Video'yu başlat
        if (mediaPlayer != null) {
            mediaPlayer.media().play(currentVideoPath);
            videoLoaded = true;
            onLog("▶ Playing: " + videoName);
            logger.info("Video oynatılıyor: {}", currentVideoPath);
            
            // Status'u güncelle
            SwingUtilities.invokeLater(() -> 
                statusLabel.setText("Status: Playing " + videoName)
            );
        }
    }
    
    /**
     * Full screen mode toggle
     */
    private void toggleFullScreen() {
        if (mediaPlayer == null) {
            onLog("⚠ Video player hazır değil");
            return;
        }
        
        if (!isFullScreen) {
            // Full screen mode'a geç
            fullScreenFrame = new JFrame("Video Player - Full Screen");
            fullScreenFrame.setUndecorated(true);
            fullScreenFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            fullScreenFrame.setBackground(Color.BLACK);
            fullScreenFrame.getContentPane().setBackground(Color.BLACK);
            
            // Video surface'i fullscreen frame'e taşı
            playerPanel.remove(videoSurfacePanel);
            fullScreenFrame.add(videoSurfacePanel, BorderLayout.CENTER);
            
            // Kontrol paneli (basit - sadece ESC bilgisi)
            JPanel fsControlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
            fsControlPanel.setBackground(new Color(0, 0, 0, 180));
            JLabel escLabel = new JLabel("Press ESC or double-click to exit full screen");
            escLabel.setForeground(Color.WHITE);
            escLabel.setFont(escLabel.getFont().deriveFont(12f));
            fsControlPanel.add(escLabel);
            fullScreenFrame.add(fsControlPanel, BorderLayout.SOUTH);
            
            // ESC tuşu ile çık
            fullScreenFrame.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_ESCAPE ||
                        e.getKeyCode() == java.awt.event.KeyEvent.VK_F) {
                        toggleFullScreen();
                    }
                    // Space ile pause/play
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_SPACE) {
                        if (mediaPlayer.status().isPlaying()) {
                            mediaPlayer.controls().pause();
                        } else {
                            mediaPlayer.controls().play();
                        }
                    }
                }
            });
            
            // Çift tıklama ile çık
            videoSurfacePanel.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2 && isFullScreen) {
                        toggleFullScreen();
                    }
                }
            });
            
            fullScreenFrame.setVisible(true);
            fullScreenFrame.requestFocus();
            isFullScreen = true;
            onLog("⛶ Full screen mode enabled (ESC to exit)");
            logger.info("Full screen mode aktif");
            
        } else {
            // Normal mode'a dön
            if (fullScreenFrame != null) {
                fullScreenFrame.remove(videoSurfacePanel);
                fullScreenFrame.dispose();
                fullScreenFrame = null;
            }
            
            // Video surface'i geri ekle
            playerPanel.add(videoSurfacePanel, BorderLayout.CENTER);
            playerPanel.revalidate();
            playerPanel.repaint();
            
            isFullScreen = false;
            onLog("⛶ Exited full screen mode");
            logger.info("Full screen mode kapatıldı");
        }
    }
    
    /**
     * Zamanı formatla (ms -> MM:SS)
     */
    private String formatTime(long milliseconds) {
        if (milliseconds <= 0) return "00:00";
        long seconds = milliseconds / 1000;
        long minutes = seconds / 60;
        seconds = seconds % 60;
        return String.format("%02d:%02d", minutes, seconds);
    }
    
    /**
     * Alt panel: Folder Info + Event Log + Status Bar
     */
    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        
        // === Üst: Folder bilgileri ===
        JPanel folderInfoPanel = new JPanel(new GridLayout(1, 2, 20, 0));
        folderInfoPanel.setBackground(new Color(245, 245, 245));
        folderInfoPanel.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(200, 200, 200)),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)
        ));
        
        // Root Video Folder
        JPanel rootPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        rootPanel.setOpaque(false);
        JLabel rootIcon = new JLabel("📁");
        JLabel rootTitle = new JLabel("Root Video Folder: ");
        rootTitle.setFont(rootTitle.getFont().deriveFont(Font.BOLD, 11f));
        rootFolderLabel = new JLabel("Not Set");
        rootFolderLabel.setForeground(new Color(100, 100, 100));
        rootFolderLabel.setFont(rootFolderLabel.getFont().deriveFont(11f));
        rootPanel.add(rootIcon);
        rootPanel.add(rootTitle);
        rootPanel.add(rootFolderLabel);
        
        // Buffer Folder
        JPanel bufferPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        bufferPanel.setOpaque(false);
        JLabel bufferIcon = new JLabel("💾");
        JLabel bufferTitle = new JLabel("Buffer Folder: ");
        bufferTitle.setFont(bufferTitle.getFont().deriveFont(Font.BOLD, 11f));
        bufferFolderLabel = new JLabel("Not Set");
        bufferFolderLabel.setForeground(new Color(100, 100, 100));
        bufferFolderLabel.setFont(bufferFolderLabel.getFont().deriveFont(11f));
        bufferPanel.add(bufferIcon);
        bufferPanel.add(bufferTitle);
        bufferPanel.add(bufferFolderLabel);
        
        folderInfoPanel.add(rootPanel);
        folderInfoPanel.add(bufferPanel);
        
        panel.add(folderInfoPanel, BorderLayout.NORTH);
        
        // === Orta: Event Log ===
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(BorderFactory.createTitledBorder("Event Log"));
        
        eventLogArea = new JTextArea(5, 50);
        eventLogArea.setEditable(false);
        eventLogArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane logScrollPane = new JScrollPane(eventLogArea);
        logPanel.add(logScrollPane, BorderLayout.CENTER);
        
        panel.add(logPanel, BorderLayout.CENTER);
        
        // === Alt: Status bar ===
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 5));
        statusPanel.setBorder(BorderFactory.createEtchedBorder());
        
        statusLabel = new JLabel("Status: Not Connected");
        
        // Peer sayısı göstergesi
        peerCountLabel = new JLabel("Peers: 0");
        peerCountLabel.setForeground(new Color(33, 150, 243)); // Blue
        peerCountLabel.setFont(peerCountLabel.getFont().deriveFont(Font.BOLD));
        
        JLabel bufferProgressLabel = new JLabel("Global Buffer:");
        globalBufferBar = new JProgressBar(0, 100);
        globalBufferBar.setStringPainted(true);
        globalBufferBar.setPreferredSize(new Dimension(200, 20));
        
        statusPanel.add(statusLabel);
        statusPanel.add(Box.createHorizontalStrut(20));
        statusPanel.add(peerCountLabel);
        statusPanel.add(Box.createHorizontalStrut(50));
        statusPanel.add(bufferProgressLabel);
        statusPanel.add(globalBufferBar);
        
        panel.add(statusPanel, BorderLayout.SOUTH);
        
        return panel;
    }
    
    /**
     * UI update timer başlat
     */
    private void startUIUpdateTimer() {
        uiUpdateTimer = new javax.swing.Timer(500, e -> updateActiveStreams()); // 500ms - daha hızlı güncelleme
        uiUpdateTimer.start();
    }
    
    /**
     * Aktif stream'leri güncelle
     */
    private void updateActiveStreams() {
        SwingUtilities.invokeLater(() -> {
            Map<String, DownloadSession> sessions = controller.getActiveSessions();
            
            // Table'ı temizle
            streamTableModel.setRowCount(0);
            
            // Session'ları ekle
            double totalProgress = 0;
            int count = 0;
            
            for (DownloadSession session : sessions.values()) {
                VideoInfo videoInfo = session.getVideoInfo();
                
                // Gerçek peer IP'lerini göster - tüm IP'leri "-" ile ayırarak göster
                List<String> peerIPs = session.getPeerIPs();
                String peerIpDisplay;
                if (peerIPs.isEmpty()) {
                    peerIpDisplay = "No peers";
                } else {
                    // Tüm IP'leri "-" ile birleştir
                    peerIpDisplay = String.join(" - ", peerIPs);
                }
                
                double progress = session.getProgress();
                String status = session.getStatus().toString();
                
                // COMPLETED durumundaki session'ları "Completed" olarak göster
                if (session.getStatus() == DownloadSession.Status.COMPLETED) {
                    int totalChunks = videoInfo.getChunkCount();
                    streamTableModel.addRow(new Object[]{
                        videoInfo.getDisplayName(),
                        peerIpDisplay,
                        "✓ " + totalChunks,
                        "100.0",
                        "✅ Completed"
                    });
                    // Progress hesaplamasına dahil etme (zaten tamamlanmış)
                    continue;
                }
                
                // ✅ DOWNLOADING durumunda ama video oynatılmamışsa otomatik oynatmayı dene
                if (status.equals("DOWNLOADING") && session.isBufferingComplete() && 
                    !autoPlayAttempted.contains(videoInfo.getFileHash())) {
                    Path outputFile = session.getOutputFile();
                    if (outputFile != null && java.nio.file.Files.exists(outputFile)) {
                        String filePath = outputFile.toAbsolutePath().toString();
                        
                        // Eğer bu video şu an oynatılmıyorsa, otomatik oynat
                        if (mediaPlayer != null && 
                            (currentVideoPath == null || !currentVideoPath.equals(filePath))) {
                            try {
                                // Önceki video varsa durdur
                                if (videoLoaded && currentVideoPath != null) {
                                    mediaPlayer.controls().stop();
                                }
                                
                                currentVideoPath = filePath;
                                mediaPlayer.media().play(filePath);
                                videoLoaded = true;
                                
                                autoPlayAttempted.add(videoInfo.getFileHash());
                                
                                onLog("▶ Auto-playing video (DOWNLOADING): " + videoInfo.getDisplayName());
                                logger.info("DOWNLOADING durumunda otomatik oynatma: {}", filePath);
                            } catch (Exception e) {
                                logger.error("DOWNLOADING durumunda otomatik oynatma hatası", e);
                                onLog("⚠ Auto-play failed: " + e.getMessage());
                            }
                        }
                    }
                }
                
                // Status gösterimi - DOWNLOADING durumunda "Streaming" olarak göster, emoji yok
                String statusDisplay;
                if (status.equals("DOWNLOADING")) {
                    statusDisplay = "Streaming";
                } else {
                    statusDisplay = status;
                }
                
                // Chunk bilgisi hesapla
                int totalChunks = videoInfo.getChunkCount();
                int receivedChunks = (int) Math.round(progress * totalChunks / 100.0);
                String chunkDisplay = receivedChunks + "/" + totalChunks;
                
                streamTableModel.addRow(new Object[]{
                    videoInfo.getDisplayName(),
                    peerIpDisplay,
                    chunkDisplay,
                    String.format("%.1f", progress),
                    statusDisplay
                });
                
                totalProgress += progress;
                count++;
            }
            
            // Tamamlanmış indirmeleri göster (session'da olmayanlar - kalıcı, çift tıkla oynat)
            // Session'da olan COMPLETED durumundakiler zaten yukarıda gösterildi
            Set<String> activeSessionHashes = new HashSet<>();
            for (DownloadSession session : sessions.values()) {
                activeSessionHashes.add(session.getVideoInfo().getFileHash());
            }
            
            for (CompletedDownload cd : completedDownloads.values()) {
                // Sadece session'da olmayan tamamlanmış indirmeleri göster
                // (Session'da olanlar zaten yukarıda COMPLETED olarak gösterildi)
                String videoHash = null;
                for (Map.Entry<String, CompletedDownload> entry : completedDownloads.entrySet()) {
                    if (entry.getValue() == cd) {
                        videoHash = entry.getKey();
                        break;
                    }
                }
                
                if (videoHash != null && !activeSessionHashes.contains(videoHash)) {
                    streamTableModel.addRow(new Object[]{
                        cd.videoName,
                        cd.peerIp,
                        "✓ " + cd.chunkCount,  // Toplam chunk sayısı
                        "100.0",
                        "✅ Completed"
                    });
                }
            }
            
            // Global buffer güncelle
            if (count > 0) {
                int avgProgress = (int) (totalProgress / count);
                globalBufferBar.setValue(avgProgress);
                globalBufferBar.setString(avgProgress + "%");
            } else if (!completedDownloads.isEmpty()) {
                globalBufferBar.setValue(100);
                globalBufferBar.setString("100%");
            } else {
                globalBufferBar.setValue(0);
                globalBufferBar.setString("0%");
            }
            
            // Peer sayısını güncelle
            int peerCount = controller.getPeerManager().getActivePeers().size();
            peerCountLabel.setText("Peers: " + peerCount);
            if (peerCount > 0) {
                peerCountLabel.setForeground(new Color(76, 175, 80)); // Green
            } else {
                peerCountLabel.setForeground(new Color(158, 158, 158)); // Gray
            }
        });
    }
    
    // ==================== Event Handlers ====================
    
    private void handleConnect() {
        try {
            controller.connect();
            onLog("Connected to P2P network - Listening for peers...");
            
            SwingUtilities.invokeLater(() -> {
                statusLabel.setText("Status: Connected - Discovering peers...");
                connectItem.setEnabled(false);  // Connect butonunu disable et
                disconnectItem.setEnabled(true); // Disconnect butonunu enable et
                searchButton.setEnabled(true);   // Search butonunu enable et
            });
            
            // Peer discovery için dinlemeye başla
            startPeerDiscoveryMonitoring();
            
            JOptionPane.showMessageDialog(this, 
                "Successfully connected to P2P network\nListening for peers...", 
                "Connected", 
                JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            logger.error("Bağlantı hatası", e);
            onLog("Connection error: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Failed to connect: " + e.getMessage(), 
                "Connection Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * Peer discovery monitoring başlat
     */
    private void startPeerDiscoveryMonitoring() {
        new Thread(() -> {
            try {
                Thread.sleep(2000); // Discovery broadcast'inin gitmesi için bekle
                
                int peerCount = controller.getPeerManager().getActivePeers().size();
                onLog("Peer discovery scan: " + peerCount + " peer(s) found");
                
                if (peerCount == 0) {
                    onLog("No other peers found yet. Make sure other peers are:");
                    onLog("  1. Running on the same network");
                    onLog("  2. Have clicked 'Connect' button");
                    onLog("  3. Not blocked by firewall");
                } else {
                    onLog("Ready to search videos from " + peerCount + " peer(s)");
                }
                
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "PeerDiscoveryMonitor").start();
    }
    
    private void handleDisconnect() {
        controller.disconnect();
        onLog("Disconnected from P2P network");
        
        SwingUtilities.invokeLater(() -> {
            statusLabel.setText("Status: Disconnected");
            videoListModel.clear();
            streamTableModel.setRowCount(0);
            connectItem.setEnabled(true);     // Connect butonunu enable et
            disconnectItem.setEnabled(false); // Disconnect butonunu disable et
            searchButton.setEnabled(false);   // Search butonunu disable et
        });
        
        JOptionPane.showMessageDialog(this, 
            "Disconnected from P2P network", 
            "Disconnected", 
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void handleSetRootFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Root Video Folder");
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            try {
                controller.setRootFolder(selected.toPath());
                int videoCount = controller.getVideoCatalog().size();
                
                // UI güncelle
                String shortPath = shortenPath(selected.getAbsolutePath(), 35);
                rootFolderLabel.setText(shortPath + " (" + videoCount + " videos)");
                rootFolderLabel.setForeground(new Color(46, 125, 50)); // Yeşil
                rootFolderLabel.setToolTipText(selected.getAbsolutePath()); // Full path tooltip
                
                onLog("📁 Root folder: " + selected.getAbsolutePath() + " (" + videoCount + " videos)");
            } catch (Exception e) {
                logger.error("Root folder ayarlama hatası", e);
                onLog("Error setting root folder: " + e.getMessage());
                JOptionPane.showMessageDialog(this, 
                    "Failed to set root folder: " + e.getMessage(), 
                    "Error", 
                    JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void handleSetBufferFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Buffer Folder");
        
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File selected = chooser.getSelectedFile();
            controller.setBufferFolder(selected.toPath());
            
            // UI güncelle
            String shortPath = shortenPath(selected.getAbsolutePath(), 35);
            bufferFolderLabel.setText(shortPath);
            bufferFolderLabel.setForeground(new Color(46, 125, 50)); // Yeşil
            bufferFolderLabel.setToolTipText(selected.getAbsolutePath()); // Full path tooltip
            
            onLog("💾 Buffer folder: " + selected.getAbsolutePath());
        }
    }
    
    /**
     * Uzun path'i kısaltır (örn: /Users/.../Documents/Videos)
     */
    private String shortenPath(String path, int maxLength) {
        if (path.length() <= maxLength) {
            return path;
        }
        
        // Son klasör adını al
        String[] parts = path.split(File.separator.equals("\\") ? "\\\\" : "/");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            int remainingLength = maxLength - lastPart.length() - 4; // ".../" için
            
            if (remainingLength > 5) {
                String start = path.substring(0, remainingLength);
                return start + ".../" + lastPart;
            }
        }
        
        // Çok kısaysa sadece son kısmı göster
        return "..." + path.substring(path.length() - maxLength + 3);
    }
    
    private void handleSearch() {
        if (!controller.isConnected()) {
            JOptionPane.showMessageDialog(this, 
                "Please connect to network first", 
                "Not Connected", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        String query = searchField.getText().trim();
        if (query.isEmpty()) {
            query = ""; // Boş query ile tüm videoları ara
        }
        
        controller.searchVideos(query);
        onLog("Searching for videos: '" + query + "'");
        SwingUtilities.invokeLater(() -> statusLabel.setText("Status: Searching..."));
        
        // Arama sonuçlarını almak için background thread başlat
        String finalQuery = query;
        new Thread(() -> {
            try {
                Thread.sleep(3000); // Peer'ların cevap vermesi için bekle (3 saniye)
                // Query'ye göre filtreleme yaparak sonuçları al
                List<VideoInfo> results = controller.getPeerManager().searchVideos(finalQuery);
                
                // Exclusion filter'ları uygula - GUI'da gösterilmeden önce
                List<VideoInfo> filteredResults = results.stream()
                    .filter(video -> !controller.getVideoCatalog().isExcluded(video))
                    .collect(java.util.stream.Collectors.toList());
                
                int excludedCount = results.size() - filteredResults.size();
                if (excludedCount > 0) {
                    onLog("Exclusion filter: " + excludedCount + " video filtrelendi");
                }
                
                onLog("Search completed: " + filteredResults.size() + " videos found");
                onSearchResults(filteredResults);
                
                // Eğer sonuç yoksa kullanıcıya bildir
                if (results.isEmpty()) {
                    SwingUtilities.invokeLater(() -> {
                        onLog("No videos found. Make sure other peers have:");
                        onLog("1. Connected to network");
                        onLog("2. Set their root video folder");
                        onLog("3. Have video files in their folder");
                    });
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    private void handleStreamVideo() {
        VideoInfo selected = availableVideosList.getSelectedValue();
        if (selected == null) {
            return;
        }
        
        if (controller.getBufferFolder() == null) {
            JOptionPane.showMessageDialog(this, 
                "Please set buffer folder first", 
                "Buffer Folder Not Set", 
                JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        try {
            DownloadSession session = controller.startStreaming(selected);
            onLog(" Streaming started: " + selected.getDisplayName());
            logger.info("DownloadSession başlatıldı: {}, callback ayarlanıyor...", session.getSessionId());
            
            // Hemen tabloyu güncelle (indirme başladığını göster)
            updateActiveStreams();
            
            // Buffering complete callback - Video hazır
            session.setOnBufferingComplete(() -> {
                logger.info("🎬 Buffering complete callback çağrıldı!");
                SwingUtilities.invokeLater(() -> {
                    try {
                        Path outputFile = session.getOutputFile();
                        logger.info("Output file: {}", outputFile.toAbsolutePath());
                        
                        if (outputFile != null && java.nio.file.Files.exists(outputFile)) {
                            logger.info("File exists: {} bytes", java.nio.file.Files.size(outputFile));
                            
                            // Path'i kaydet
                            String newVideoPath = outputFile.toAbsolutePath().toString();
                            
                            // ✅ ÖNEMLİ: Eğer aynı video zaten oynatılıyorsa, hiçbir şey yapma (baştan başlamasın)
                            if (mediaPlayer != null && videoLoaded && 
                                currentVideoPath != null && currentVideoPath.equals(newVideoPath)) {
                                try {
                                    boolean isPlaying = mediaPlayer.status().isPlaying();
                                    if (isPlaying) {
                                        logger.info("Video zaten oynatılıyor (buffering complete), hiçbir şey yapılmıyor: {}", newVideoPath);
                                        // Video oynatılıyor, hiçbir şey yapma - sadece tabloyu güncelle
                                        updateActiveStreams();
                                        return; // Callback'ten çık, tekrar başlatma
                                    } else {
                                        logger.info("Video yüklenmiş ama durmuş (buffering complete), devam ettiriliyor: {}", newVideoPath);
                                        // Video durmuş, devam ettir (ama pozisyon korunmalı)
                                        mediaPlayer.controls().play();
                                        updateActiveStreams();
                                        return; // Callback'ten çık
                                    }
                                } catch (Exception e) {
                                    logger.warn("Video durumu kontrol edilemedi: {}", e.getMessage());
                                }
                            }
                            
                            // ✅ SPEC GEREKSİNİMİ: Buffering tamamlandığında OTOMATIK OYNAT
                            // "playback should start as soon as the required number of chunks is buffered"
                            if (mediaPlayer != null) {
                                try {
                                    // Önceki video varsa durdur (sadece farklı video ise)
                                    if (videoLoaded && currentVideoPath != null && !currentVideoPath.equals(newVideoPath)) {
                                        mediaPlayer.controls().stop();
                                    }
                                    
                                    currentVideoPath = newVideoPath;
                                    mediaPlayer.media().play(currentVideoPath);
                                    videoLoaded = true;
                                    
                                    onLog("▶ Auto-playing video (buffered): " + selected.getDisplayName());
                                    logger.info("Video otomatik oynatılıyor (buffering tamamlandı, streaming while downloading): {}", currentVideoPath);
                                } catch (Exception e) {
                                    logger.error("Otomatik oynatma hatası", e);
                                    onLog("⚠ Auto-play failed: " + e.getMessage());
                                    videoLoaded = false;
                                }
                            } else {
                                logger.warn("Video player hazır değil, otomatik oynatma yapılamadı");
                                videoLoaded = false;
                            }
                            
                            // Hemen tabloyu güncelle
                            updateActiveStreams();
                            
                        } else {
                            logger.error("Output file mevcut değil: {}", outputFile);
                            onLog("ERROR: Downloaded file not found!");
                        }
                    } catch (Exception e) {
                        logger.error("Video hazırlama hatası", e);
                        onLog("Video preparation error: " + e.getMessage());
                    }
                });
            });
            
            // Download complete callback - İndirme tamamlandığında
            session.setOnDownloadComplete(() -> {
                logger.info("✅ Download complete callback çağrıldı!");
                SwingUtilities.invokeLater(() -> {
                    try {
                        Path outputFile = session.getOutputFile();
                        if (outputFile != null && java.nio.file.Files.exists(outputFile)) {
                            String filePath = outputFile.toAbsolutePath().toString();
                            
                            // ✅ ÖNEMLİ: Eğer bu video zaten oynatılıyorsa, video durmasın, devam etsin
                            // Video oynatılıyorsa hiçbir şey yapma, sadece map'e ekle
                            boolean isSameVideo = (currentVideoPath != null && currentVideoPath.equals(filePath));
                            boolean isVideoLoaded = (mediaPlayer != null && videoLoaded && isSameVideo);
                            
                            if (isVideoLoaded) {
                                // Video zaten yüklenmiş ve aynı dosya, oynatma durumunu kontrol et
                                try {
                                    boolean isPlaying = mediaPlayer.status().isPlaying();
                                    if (isPlaying) {
                                        logger.info("Video zaten oynatılıyor, hiçbir şey yapılmıyor (baştan başlamasın diye): {}", filePath);
                                        // Video oynatılıyor, hiçbir şey yapma - sadece map'e ekle
                                        // play() çağırmıyoruz çünkü bu video'yu baştan başlatır!
                                    } else {
                                        // Video yüklenmiş ama durmuş, mevcut pozisyonu koruyarak devam ettir
                                        long currentTime = mediaPlayer.status().time();
                                        logger.info("Video durmuş, mevcut pozisyondan devam ettiriliyor (time={}ms): {}", currentTime, filePath);
                                        // play() çağırmıyoruz, sadece resume ediyoruz (ama VLCJ'de play() resume yapar)
                                        // Ama dikkat: eğer video durmuşsa, play() çağırmak gerekir ama pozisyon korunmalı
                                        if (currentTime > 0) {
                                            // Pozisyonu koru
                                            mediaPlayer.controls().setTime(currentTime);
                                        }
                                        mediaPlayer.controls().play();
                                        onLog("▶ Resuming video playback: " + selected.getDisplayName());
                                    }
                                } catch (Exception e) {
                                    logger.warn("Video oynatma durumu kontrol edilemedi, devam ediliyor: {}", e.getMessage());
                                }
                            } else if (mediaPlayer != null && !isSameVideo) {
                                // Video oynatılmıyor ama player hazır, oynatmayı başlat
                                try {
                                    // Önceki video varsa durdur
                                    if (videoLoaded && currentVideoPath != null) {
                                        mediaPlayer.controls().stop();
                                    }
                                    
                                    currentVideoPath = filePath;
                                    mediaPlayer.media().play(currentVideoPath);
                                    videoLoaded = true;
                                    
                                    onLog("▶ Auto-playing completed video: " + selected.getDisplayName());
                                    logger.info("Tamamlanan video otomatik oynatılıyor: {}", currentVideoPath);
                                } catch (Exception e) {
                                    logger.error("Tamamlanan video oynatma hatası", e);
                                    onLog("⚠ Auto-play failed: " + e.getMessage());
                                }
                            } else {
                                // Video player hazır değil veya video zaten yüklenmiş
                                logger.info("Download complete - Video player durumu: loaded={}, path={}, filePath={}", 
                                    videoLoaded, currentVideoPath, filePath);
                            }
                            
                            // Tamamlanan indirmeyi listeye ekle (Active Streams'de göster)
                            List<String> peerIPs = session.getPeerIPs();
                            String peerIp = peerIPs.isEmpty() ? "Unknown" : peerIPs.get(0);
                            
                            // currentVideoPath'i kaydet
                            if (currentVideoPath == null || currentVideoPath.isEmpty()) {
                                currentVideoPath = filePath;
                            }
                            
                            completedDownloads.put(selected.getFileHash(), 
                                new CompletedDownload(selected.getDisplayName(), peerIp, currentVideoPath, selected.getChunkCount()));
                            
                            onLog("✅ Download completed: " + selected.getDisplayName());
                            logger.info("İndirme tamamlandı ve completedDownloads'a eklendi: {}", selected.getDisplayName());
                            
                            // Hemen tabloyu güncelle
                            updateActiveStreams();
                        }
                    } catch (Exception e) {
                        logger.error("Download complete callback hatası", e);
                        onLog("Download complete error: " + e.getMessage());
                    }
                });
            });
            
            SwingUtilities.invokeLater(() -> 
                statusLabel.setText("Status: Streaming " + selected.getDisplayName())
            );
            
        } catch (Exception e) {
            logger.error("Streaming başlatma hatası", e);
            onLog("Streaming error: " + e.getMessage());
            JOptionPane.showMessageDialog(this, 
                "Failed to start streaming: " + e.getMessage(), 
                "Streaming Error", 
                JOptionPane.ERROR_MESSAGE);
        }
    }
    
    /**
     * ✅ BONUS: Exclusion Filters Dialog
     * Dosya adı, klasör, uzantı ve wildcard pattern ile filtreleme
     */
    private void handleExclusionFilters() {
        JDialog dialog = new JDialog(this, "Exclusion Filters", true);
        dialog.setSize(500, 450);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout(10, 10));
        
        // Help text
        JPanel helpPanel = new JPanel(new BorderLayout());
        helpPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 5, 10));
        JTextArea helpText = new JTextArea(
            "Exclude files from video catalog using filters:\n\n" +
            "• keyword             - Filename keyword (e.g., 'test', 'backup')\n" +
            "• folder:/path/to     - Folder path (e.g., 'folder:/videos/private')\n" +
            "• .ext                - File extension (e.g., '.avi', '.flv')\n\n" +
            "Examples:\n" +
            "  test                (excludes files with 'test' in filename)\n" +
            "  folder:/videos/private\n" +
            "  .avi\n" +
            "  .flv\n\n" +
            "Lines starting with # are comments."
        );
        helpText.setEditable(false);
        helpText.setBackground(new Color(245, 245, 245));
        helpText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        helpText.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color.LIGHT_GRAY),
            BorderFactory.createEmptyBorder(5, 5, 5, 5)
        ));
        helpPanel.add(helpText, BorderLayout.CENTER);
        
        // Filter text area
        JPanel filterPanel = new JPanel(new BorderLayout());
        filterPanel.setBorder(BorderFactory.createTitledBorder("Filter Rules"));
        JTextArea filterArea = new JTextArea(10, 40);
        filterArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        filterArea.setText(controller.getVideoCatalog().getFiltersAsText());
        JScrollPane scrollPane = new JScrollPane(filterArea);
        filterPanel.add(scrollPane, BorderLayout.CENTER);
        
        // Status label
        JLabel statusLabel = new JLabel("Current filters: " + 
            controller.getVideoCatalog().getExclusionFilterCount());
        statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        
        // Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        
        JButton clearButton = new JButton("Clear All");
        clearButton.addActionListener(e -> {
            filterArea.setText("");
            statusLabel.setText("Filters cleared (not saved yet)");
        });
        
        JButton applyButton = new JButton("Apply & Rescan");
        applyButton.addActionListener(e -> {
            try {
                controller.getVideoCatalog().parseAndSetFilters(filterArea.getText());
                int filterCount = controller.getVideoCatalog().getExclusionFilterCount();
                statusLabel.setText("Applied " + filterCount + " filter(s)");
                
                // Rescan if root folder is set
                if (controller.getVideoCatalog().getRootFolder() != null) {
                    controller.getVideoCatalog().scanFolder();
                    int videoCount = controller.getVideoCatalog().size();
                    rootFolderLabel.setText(shortenPath(
                        controller.getVideoCatalog().getRootFolder().toString(), 35) + 
                        " (" + videoCount + " videos)");
                    onLog("🔍 Exclusion filters applied. " + filterCount + " filter(s), " + 
                          videoCount + " video(s) in catalog");
                    
                    // ✅ DÜZELTME: Sol taraftaki video listesini güncelle
                    // Yerel catalog'daki videoları ve network'teki videoları birleştir
                    SwingUtilities.invokeLater(() -> {
                        // Mevcut network videolarını al
                        List<VideoInfo> networkVideos = controller.getPeerManager().getAllAvailableVideos();
                        
                        // Yerel catalog'daki videoları al
                        List<VideoInfo> localVideos = controller.getVideoCatalog().getAllVideos();
                        
                        // Birleştir (duplicate kontrolü - fileHash'e göre)
                        Set<String> seenHashes = new HashSet<>();
                        List<VideoInfo> allVideos = new ArrayList<>();
                        
                        // Önce yerel videoları ekle
                        for (VideoInfo video : localVideos) {
                            if (!seenHashes.contains(video.getFileHash())) {
                                allVideos.add(video);
                                seenHashes.add(video.getFileHash());
                            }
                        }
                        
                        // Sonra network videolarını ekle (yerel olmayanlar)
                        for (VideoInfo video : networkVideos) {
                            if (!seenHashes.contains(video.getFileHash())) {
                                allVideos.add(video);
                                seenHashes.add(video.getFileHash());
                            }
                        }
                        
                        // Exclusion filter'ları uygula - GUI'da gösterilmeden önce
                        List<VideoInfo> filteredVideos = allVideos.stream()
                            .filter(video -> !controller.getVideoCatalog().isExcluded(video))
                            .collect(java.util.stream.Collectors.toList());
                        
                        // Listeyi güncelle
                        videoListModel.clear();
                        for (VideoInfo video : filteredVideos) {
                            videoListModel.addElement(video);
                        }
                        
                        int excludedCount = allVideos.size() - filteredVideos.size();
                        onLog("📋 Video list updated: " + filteredVideos.size() + " videos shown " +
                              "(local: " + localVideos.size() + ", network: " + networkVideos.size() + 
                              (excludedCount > 0 ? ", excluded: " + excludedCount : "") + ")");
                        statusLabel.setText("Status: Connected (" + filteredVideos.size() + " videos available)");
                    });
                }
                
                JOptionPane.showMessageDialog(dialog,
                    "Filters applied successfully!\n" +
                    "Active filters: " + filterCount + "\n" +
                    "Videos in catalog: " + controller.getVideoCatalog().size(),
                    "Filters Applied",
                    JOptionPane.INFORMATION_MESSAGE);
                    
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(dialog,
                    "Error applying filters: " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
            }
        });
        
        JButton cancelButton = new JButton("Close");
        cancelButton.addActionListener(e -> dialog.dispose());
        
        buttonPanel.add(clearButton);
        buttonPanel.add(applyButton);
        buttonPanel.add(cancelButton);
        
        // Add to dialog
        dialog.add(helpPanel, BorderLayout.NORTH);
        dialog.add(filterPanel, BorderLayout.CENTER);
        
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(statusLabel, BorderLayout.WEST);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        dialog.add(bottomPanel, BorderLayout.SOUTH);
        
        dialog.setVisible(true);
    }
    
    private void showAboutDialog() {
        String message = "Developer: Özer Efe Engin\n" +
                        "Student ID: 20210702033\n" +
                        "Course: CSE471 - Data Communications and Computer Networks\n\n" +
                        "P2P Video Streaming Application\n" +
                        "Version 1.0 - Swing UI with VLCJ\n\n" +
                        "Features:\n" +
                        "• Peer-to-peer video discovery (UDP)\n" +
                        "• Multi-source chunk streaming (TCP)\n" +
                        "• 256KB chunk-based transfer\n" +
                        "• SHA-256 file hashing\n" +
                        "• Buffer-while-download playback\n" +
                        "• Exclusion filters for video catalog";
        
        JOptionPane.showMessageDialog(this, 
            message, 
            "About Developer", 
            JOptionPane.INFORMATION_MESSAGE);
    }
    
    private void handleClose() {
        logger.info("Uygulama kapatılıyor...");
        onLog("Application closing...");
        
        if (uiUpdateTimer != null) {
            uiUpdateTimer.stop();
        }
        
        controller.disconnect();
        
        if (mediaPlayer != null) {
            mediaPlayer.release();
        }
        
        if (mediaPlayerFactory != null) {
            mediaPlayerFactory.release();
        }
        
        dispose();
        System.exit(0);
    }
    
    // ==================== UICallback Implementation ====================
    
    @Override
    public void onLog(String message) {
        SwingUtilities.invokeLater(() -> {
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss").format(new Date());
            eventLogArea.append("[" + timestamp + "] " + message + "\n");
            eventLogArea.setCaretPosition(eventLogArea.getDocument().getLength());
        });
    }
    
    @Override
    public void onSearchResults(List<VideoInfo> results) {
        SwingUtilities.invokeLater(() -> {
            // Exclusion filter'ları tekrar uygula (güvenlik için)
            List<VideoInfo> filteredResults = results.stream()
                .filter(video -> !controller.getVideoCatalog().isExcluded(video))
                .collect(java.util.stream.Collectors.toList());
            
            videoListModel.clear();
            for (VideoInfo video : filteredResults) {
                videoListModel.addElement(video);
            }
            onLog("Search completed: " + filteredResults.size() + " videos found (exclusion filters applied)");
            statusLabel.setText("Status: Connected (" + filteredResults.size() + " videos available)");
        });
    }
    
    @Override
    public void onStreamProgress(String videoId, String peerIp, double percent, String status) {
        // Already handled by updateActiveStreams timer
    }
    
    @Override
    public void onBufferStatus(String videoId, double percentBuffered) {
        // Already handled by updateActiveStreams timer
    }
    
    @Override
    public void onConnectionStatusChanged(boolean connected) {
        SwingUtilities.invokeLater(() -> {
            String status = connected ? "Connected" : "Disconnected";
            statusLabel.setText("Status: " + status);
            onLog("Connection status: " + status);
        });
    }
    
    // ==================== Custom Cell Renderer ====================
    
    /**
     * Video list cell renderer
     */
    private static class VideoListCellRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, 
                                                     int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            
            if (value instanceof VideoInfo) {
                VideoInfo video = (VideoInfo) value;
                setText(video.getDisplayName() + " [" + video.getChunkCount() + " chunks]");
            }
            
            return this;
        }
    }
    
    // ==================== Video Surface Panel (Callback Mode) ====================
    
    /**
     * Video rendering için özel JPanel.
     * VLCJ CallbackVideoSurface ile çalışır - macOS için gereken yöntem!
     * Her video frame'i BufferedImage'a çizilir ve paintComponent ile ekrana basılır.
     */
    private class VideoSurfacePanel extends JPanel {
        private BufferedImage image;
        private final Object imageLock = new Object();
        
        public VideoSurfacePanel() {
            setOpaque(true);
            setBackground(Color.BLACK);
        }
        
        /**
         * BufferFormat callback - video boyutları değiştiğinde çağrılır
         */
        public BufferFormatCallback getBufferFormatCallback() {
            return new BufferFormatCallback() {
                @Override
                public BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
                    synchronized (imageLock) {
                        image = new BufferedImage(sourceWidth, sourceHeight, BufferedImage.TYPE_INT_RGB);
                    }
                    logger.info("Video buffer format: {}x{}", sourceWidth, sourceHeight);
                    return new RV32BufferFormat(sourceWidth, sourceHeight);
                }
                
                @Override
                public void allocatedBuffers(ByteBuffer[] buffers) {
                    // Nothing needed here
                }
            };
        }
        
        /**
         * Render callback - her video frame için çağrılır
         */
        public RenderCallback getRenderCallback() {
            return new RenderCallback() {
                @Override
                public void display(MediaPlayer mediaPlayer, ByteBuffer[] nativeBuffers, BufferFormat bufferFormat) {
                    synchronized (imageLock) {
                        if (image != null && nativeBuffers.length > 0) {
                            ByteBuffer buffer = nativeBuffers[0];
                            int[] imageData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
                            buffer.asIntBuffer().get(imageData);
                        }
                    }
                    // UI thread'de repaint çağır
                    SwingUtilities.invokeLater(VideoSurfacePanel.this::repaint);
                }
            };
        }
        
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            
            Graphics2D g2d = (Graphics2D) g;
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, getWidth(), getHeight());
            
            synchronized (imageLock) {
                if (image != null) {
                    // Video'yu panel boyutuna ölçekle (aspect ratio koruyarak)
                    int panelWidth = getWidth();
                    int panelHeight = getHeight();
                    int imageWidth = image.getWidth();
                    int imageHeight = image.getHeight();
                    
                    double scaleX = (double) panelWidth / imageWidth;
                    double scaleY = (double) panelHeight / imageHeight;
                    double scale = Math.min(scaleX, scaleY);
                    
                    int scaledWidth = (int) (imageWidth * scale);
                    int scaledHeight = (int) (imageHeight * scale);
                    
                    int x = (panelWidth - scaledWidth) / 2;
                    int y = (panelHeight - scaledHeight) / 2;
                    
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2d.drawImage(image, x, y, scaledWidth, scaledHeight, null);
                }
            }
        }
    }
}

