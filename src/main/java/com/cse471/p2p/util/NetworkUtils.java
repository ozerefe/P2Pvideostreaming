package com.cse471.p2p.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.*;
import java.util.Enumeration;

/**
 * Network utility fonksiyonları
 */
public class NetworkUtils {
    private static final Logger logger = LoggerFactory.getLogger(NetworkUtils.class);
    
    /**
     * Yerel IP adresini bulur (broadcast için)
     * VirtualBox Internal Network IP'sini (192.168.x.x) tercih eder
     * NAT adapter IP'sini (10.0.2.x) atlar
     */
    public static String getLocalIPAddress() {
        try {
            String fallbackIp = null;
            
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                
                // Loopback ve down interface'leri atla
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    // IPv4 ve site-local adresi tercih et
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        
                        // 192.168.x.x IP'lerini ÖNCELİKLİ olarak tercih et (Internal Network)
                        if (ip.startsWith("192.168.")) {
                            logger.debug("Internal Network IP bulundu: {}", ip);
                            return ip;
                        }
                        
                        // 10.0.2.x IP'lerini ATLA (VirtualBox NAT)
                        if (ip.startsWith("10.0.2.")) {
                            logger.debug("NAT IP atlandı: {}", ip);
                            continue;
                        }
                        
                        // Diğer IP'leri fallback olarak sakla
                        if (fallbackIp == null) {
                            fallbackIp = ip;
                        }
                    }
                }
            }
            
            // Fallback IP varsa kullan
            if (fallbackIp != null) {
                logger.debug("Fallback IP kullanılıyor: {}", fallbackIp);
                return fallbackIp;
            }
            
            // Son fallback: localhost
            return InetAddress.getLocalHost().getHostAddress();
            
        } catch (Exception e) {
            logger.error("Yerel IP bulunamadı", e);
            return "127.0.0.1";
        }
    }
    
    /**
     * Broadcast adresi bulur (gerçek network için)
     */
    public static InetAddress getBroadcastAddress() {
        try {
            // Standard broadcast - farklı cihazlar için
            return InetAddress.getByName("255.255.255.255");
        } catch (UnknownHostException e) {
            logger.error("Broadcast adresi oluşturulamadı", e);
            return null;
        }
    }
    
    /**
     * Multicast adresi döndürür (alternatif method)
     */
    public static InetAddress getMulticastAddress() {
        try {
            // All-systems multicast group
            return InetAddress.getByName("224.0.0.1");
        } catch (UnknownHostException e) {
            logger.error("Multicast adresi oluşturulamadı", e);
            return null;
        }
    }
    
    /**
     * Port'un kullanılabilir olup olmadığını kontrol eder
     */
    public static boolean isPortAvailable(int port) {
        try (ServerSocket ss = new ServerSocket(port)) {
            ss.setReuseAddress(true);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
    
    /**
     * Kullanılabilir bir port bulur
     */
    public static int findAvailablePort(int startPort) {
        for (int port = startPort; port < startPort + 100; port++) {
            if (isPortAvailable(port)) {
                return port;
            }
        }
        return -1;
    }
    
    /**
     * Tüm local network interface'lerinin broadcast adreslerini döndürür
     * Gateway peer'lar için tüm subnet'lere broadcast göndermek için kullanılır
     */
    public static java.util.List<String> getAllBroadcastAddresses() {
        java.util.List<String> broadcastAddresses = new java.util.ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                
                // Loopback ve down interface'leri atla
                if (ni.isLoopback() || !ni.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    // IPv4 ve site-local adresi tercih et
                    if (addr instanceof Inet4Address && addr.isSiteLocalAddress()) {
                        String ip = addr.getHostAddress();
                        // Broadcast adresini hesapla (/24 subnet varsayılıyor)
                        String[] parts = ip.split("\\.");
                        if (parts.length == 4) {
                            String broadcast = parts[0] + "." + parts[1] + "." + parts[2] + ".255";
                            if (!broadcastAddresses.contains(broadcast)) {
                                broadcastAddresses.add(broadcast);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Broadcast adresleri bulunamadı", e);
        }
        return broadcastAddresses;
    }
}

