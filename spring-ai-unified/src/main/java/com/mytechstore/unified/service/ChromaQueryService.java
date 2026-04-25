package com.mytechstore.unified.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Shared Chroma query helper with cached collection-ID lookups,
 * cached embeddings, and parallelised collection-resolve + embed.
 */
@Component
public class ChromaQueryService {

    private static final Logger log = LoggerFactory.getLogger(ChromaQueryService.class);

    private final CachedEmbeddingModel embeddingModel;
    private final ChromaCollectionCache collectionCache;
    private final WebClient webClient;
    private final String chromaUrl;
    private final String collectionPrefix;
    private final ObjectMapper mapper = new ObjectMapper();

    public ChromaQueryService(CachedEmbeddingModel embeddingModel,
                              ChromaCollectionCache collectionCache,
                              WebClient webClient,
                              @Value("${chroma.url:http://chroma-faq:8000}") String chromaUrl,
                              @Value("${chroma.collection-prefix:faq_}") String collectionPrefix) {
        this.embeddingModel = embeddingModel;
        this.collectionCache = collectionCache;
        this.webClient = webClient;
        this.chromaUrl = chromaUrl;
        this.collectionPrefix = collectionPrefix;
    }

    /**
     * Queries Chroma for relevant documents. Uses cached collection IDs
     * and cached embeddings, and parallelises the two lookups.
     */
    public List<String> query(String customerId, String question, int topK) {
        if (customerId == null || customerId.isBlank()) return List.of();
        try {
            String collectionName = collectionPrefix + customerId.trim();

            // Parallelise: resolve collection ID + compute embedding concurrently
            CompletableFuture<String> collectionIdFuture = CompletableFuture.supplyAsync(
                    () -> collectionCache.getCollectionId(collectionName));
            CompletableFuture<float[]> embeddingFuture = CompletableFuture.supplyAsync(
                    () -> embeddingModel.embed(question));

            String collectionId = collectionIdFuture.join();
            float[] embArr = embeddingFuture.join();

            if (collectionId == null) return List.of();

            List<Float> embedding = new ArrayList<>(embArr.length);
            for (float f : embArr) embedding.add(f);

            Map<String, Object> queryPayload = new HashMap<>();
            queryPayload.put("query_embeddings", List.of(embedding));
            queryPayload.put("n_results", topK);
            queryPayload.put("include", List.of("documents"));

            String queryResponse = webClient.post()
                    .uri(chromaUrl + "/api/v1/collections/" + collectionId + "/query")
                    .header("Content-Type", "application/json")
                    .bodyValue(queryPayload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));

            if (queryResponse == null) return List.of();

            @SuppressWarnings("unchecked")
            Map<String, Object> result = mapper.readValue(queryResponse, Map.class);
            Object docsObj = result.get("documents");
            if (docsObj instanceof List<?> outer && !outer.isEmpty()
                    && outer.get(0) instanceof List<?> inner) {
                return inner.stream().filter(o -> o != null).map(Object::toString).toList();
            }
            return List.of();
        } catch (Exception e) {
            log.debug("Chroma query failed: {}", e.getMessage());
            return List.of();
        }
    }
}
