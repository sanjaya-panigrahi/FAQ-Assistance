package com.mytechstore.unified.service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Caches Chroma collection-name → collection-id mappings in-memory
 * to eliminate the extra GET round-trip on every query.
 */
@Component
public class ChromaCollectionCache {

    private static final Logger log = LoggerFactory.getLogger(ChromaCollectionCache.class);

    private final WebClient webClient;
    private final String chromaUrl;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public ChromaCollectionCache(WebClient webClient,
                                 @Value("${chroma.url:http://chroma-faq:8000}") String chromaUrl) {
        this.webClient = webClient;
        this.chromaUrl = chromaUrl;
    }

    /**
     * Returns the collection UUID for the given collection name,
     * using an in-memory cache to avoid repeated HTTP lookups.
     */
    @SuppressWarnings("unchecked")
    public String getCollectionId(String collectionName) {
        String cached = cache.get(collectionName);
        if (cached != null) {
            return cached;
        }

        String response = webClient.get()
                .uri(chromaUrl + "/api/v1/collections/" + collectionName)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(5));

        if (response == null) return null;

        try {
            Map<String, Object> coll = mapper.readValue(response, Map.class);
            String id = String.valueOf(coll.get("id"));
            cache.put(collectionName, id);
            return id;
        } catch (Exception e) {
            log.debug("Failed to parse collection response: {}", e.getMessage());
            return null;
        }
    }

    /** Clear cache (e.g., after re-index). */
    public void evict(String collectionName) {
        cache.remove(collectionName);
    }

    public void evictAll() {
        cache.clear();
    }
}
