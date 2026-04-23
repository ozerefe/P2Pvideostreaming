package com.cse471.p2p.protocol;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JSON serialization/deserialization için utility sınıfı
 */
public class MessageSerializer {
    private static final Logger logger = LoggerFactory.getLogger(MessageSerializer.class);
    private static final Gson gson = new GsonBuilder().create();
    
    /**
     * Objeyi JSON string'e çevirir
     */
    public static String toJson(Object obj) {
        try {
            return gson.toJson(obj);
        } catch (Exception e) {
            logger.error("JSON serialization hatası", e);
            return null;
        }
    }
    
    /**
     * JSON string'i objeye çevirir
     */
    public static <T> T fromJson(String json, Class<T> clazz) {
        try {
            return gson.fromJson(json, clazz);
        } catch (JsonSyntaxException e) {
            logger.error("JSON deserialization hatası: {}", json, e);
            return null;
        }
    }
    
    /**
     * Mesaj tipini parse eder
     */
    public static String getMessageType(String json) {
        if (json == null || json.trim().isEmpty()) {
            logger.warn("Boş JSON string");
            return null;
        }
        
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            if (obj.has("type")) {
                String type = obj.get("type").getAsString();
                if (type != null && !type.trim().isEmpty()) {
                    return type.trim();
                }
            }
            logger.warn("JSON'da 'type' field'ı yok veya boş: {}", json.substring(0, Math.min(100, json.length())));
        } catch (JsonSyntaxException e) {
            logger.error("JSON parse hatası: {}", json.substring(0, Math.min(100, json.length())), e);
        } catch (Exception e) {
            logger.error("Mesaj tipi parse edilemedi", e);
        }
        return null;
    }
}

