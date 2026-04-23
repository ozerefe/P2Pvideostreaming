package com.cse471.p2p.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.UUID;

/**
 * Swing Application ana sınıfı - JavaFX'den dönüştürülmüş
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    
    // Command line arguments
    private static int udpPort = 50000;
    private static int tcpPort = 50001;
    private static String peerName = "Peer-" + System.currentTimeMillis();
    private static String subnetId = null;
    private static boolean isGatewayPeer = false;
    private static String gatewaySubnets = null;  // Virgülle ayrılmış subnet listesi (örn: "Subnet-B,Subnet-C")
    
    public static void main(String[] args) {
        // VLC native library path for macOS
        String vlcPath = "/Applications/VLC.app/Contents/MacOS/lib";
        System.setProperty("jna.library.path", vlcPath);
        logger.info("VLC library path set: {}", vlcPath);
        
        // Command line argument parsing
        parseArguments(args);
        
        // Set Swing look and feel
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            logger.warn("Could not set system look and feel", e);
        }
        
        // Start Swing application on EDT
        SwingUtilities.invokeLater(() -> {
            try {
                logger.info("P2P Video Streaming uygulaması başlatılıyor...");
                logger.info("UDP Port: {}, TCP Port: {}, Peer Name: {}", udpPort, tcpPort, peerName);
                if (subnetId != null) {
                    logger.info("Subnet: {}, Gateway: {}", subnetId, isGatewayPeer);
                }
                
                // Peer ID oluştur
                String peerId = UUID.randomUUID().toString();
                
                // Connected subnet'leri parse et (gateway için)
                java.util.List<String> connectedSubnetsList = null;
                if (isGatewayPeer && gatewaySubnets != null && !gatewaySubnets.isEmpty()) {
                    connectedSubnetsList = new java.util.ArrayList<>();
                    String[] subnets = gatewaySubnets.split(",");
                    for (String subnet : subnets) {
                        String trimmed = subnet.trim();
                        if (!trimmed.isEmpty()) {
                            connectedSubnetsList.add(trimmed);
                        }
                    }
                }
                
                // Controller oluştur (subnet-aware)
                P2PController controller = new P2PController(
                    peerId, peerName, udpPort, tcpPort,
                    subnetId, isGatewayPeer, connectedSubnetsList
                );
                
                // Ana pencereyi oluştur ve göster
                MainFrame mainFrame = new MainFrame(controller);
                mainFrame.setVisible(true);
                
                logger.info("Uygulama başarıyla başlatıldı (Swing UI)");
                
            } catch (Exception e) {
                logger.error("Uygulama başlatma hatası", e);
                JOptionPane.showMessageDialog(null,
                    "Failed to start application: " + e.getMessage(),
                    "Startup Error",
                    JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }
    
    /**
     * Command line argümanlarını parse eder
     */
    private static void parseArguments(String[] args) {
        for (int i = 0; i < args.length; i++) {
            try {
                switch (args[i]) {
                    case "--udpPort":
                        if (i + 1 < args.length) {
                            udpPort = Integer.parseInt(args[++i]);
                        }
                        break;
                    case "--tcpPort":
                        if (i + 1 < args.length) {
                            tcpPort = Integer.parseInt(args[++i]);
                        }
                        break;
                    case "--peerName":
                        if (i + 1 < args.length) {
                            peerName = args[++i];
                        }
                        break;
                    case "--subnetId":
                        if (i + 1 < args.length) {
                            subnetId = args[++i];
                        }
                        break;
                    case "--isGateway":
                        if (i + 1 < args.length) {
                            isGatewayPeer = Boolean.parseBoolean(args[++i]);
                        }
                        break;
                    case "--gatewaySubnets":
                        if (i + 1 < args.length) {
                            gatewaySubnets = args[++i];
                        }
                        break;
                    case "--help":
                        printUsage();
                        System.exit(0);
                        break;
                }
            } catch (NumberFormatException e) {
                System.err.println("Geçersiz port numarası: " + args[i]);
                printUsage();
                System.exit(1);
            }
        }
    }
    
    /**
     * Kullanım bilgisi yazdırır
     */
    private static void printUsage() {
        System.out.println("CSE471 P2P Video Streaming Application (Swing UI)");
        System.out.println();
        System.out.println("Usage: java -jar p2p-streaming.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --udpPort <port>           UDP port for discovery (default: 50000)");
        System.out.println("  --tcpPort <port>           TCP port for chunk transfer (default: 50001)");
        System.out.println("  --peerName <name>          Peer name (default: auto-generated)");
        System.out.println("  --subnetId <id>            Subnet ID (e.g., \"Subnet-A\", \"Subnet-B\")");
        System.out.println("  --isGateway <true|false>   Is this peer a gateway? (default: false)");
        System.out.println("  --gatewaySubnets <list>    Connected subnets for gateway (comma-separated)");
        System.out.println("  --help                     Show this help message");
        System.out.println();
        System.out.println("Examples:");
        System.out.println("  # Normal peer in Subnet-A:");
        System.out.println("  java -jar p2p-streaming.jar --udpPort 50000 --tcpPort 50001 --peerName Peer1 --subnetId Subnet-A");
        System.out.println();
        System.out.println("  # Gateway peer connecting Subnet-A to Subnet-B:");
        System.out.println("  java -jar p2p-streaming.jar --udpPort 50005 --tcpPort 50006 --peerName Gateway1 --subnetId Subnet-A --isGateway true --gatewaySubnets Subnet-B");
        System.out.println();
    }
}
