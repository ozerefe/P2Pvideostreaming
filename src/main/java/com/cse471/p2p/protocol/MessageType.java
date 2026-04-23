package com.cse471.p2p.protocol;

/**
 * UDP mesaj tipleri
 */
public enum MessageType {
    DISCOVER,           // Peer discovery mesajı
    DISCOVER_REPLY,     // Discovery'ye cevap
    SEARCH,             // Video arama isteği
    SEARCH_REPLY,       // Arama sonuçları
    PEER_LIST_REQUEST,  // Peer listesi isteği
    PEER_LIST_REPLY     // Peer listesi cevabı
}

