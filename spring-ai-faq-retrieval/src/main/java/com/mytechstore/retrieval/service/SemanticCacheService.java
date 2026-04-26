package com.mytechstore.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Semantic cache using embedding similarity.
 * Paraphrased queries with cosine similarity >= threshold hit the cache,
 * avoiding redundant retrieval + generation.
 */
@Service
public class SemanticCacheService {

    private static final Logger logger = LoggerFactory.getLogger(SemanticCacheService.class);

    private static final String EMBEDDINGS_KEY_PREFIX = "semantic-cache:embeddings:";
    private static final String QUERIES_KEY_PREFIX = "semantic-cache:queries:";
    private static final String RESPONSES_KEY_PREFIX = "semantic-cache:responses:";

    private final EmbeddingModel embeddingModel;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final boolean enabled;
    private final double similarityThreshold;
    private final long ttlSeconds;
    private final int maxEntries;

    public SemanticCacheService(
            EmbeddingModel embeddingModel,
            RedisTemplate<String, Object> redisTemplate,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${semantic-cache.enabled:false}") boolean enabled,
            @Value("${semantic-cache.similarity-threshold:0.95}") double similarityThreshold,
            @Value("${semantic-cache.ttl:3600}") long ttlSeconds,
            @Value("${semantic-cache.max-entries:5000}") int maxEntries) {
        this.embeddingModel = embeddingModel;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.ttlSeconds = ttlSeconds;
        this.maxEntries = maxEntries;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Look up a semantically similar cached response.
     * Returns null on miss or when disabled.
     */
    public RetrievalQueryResponse lookup(String tenantId, String query, float[] queryEmbedding) {
        if (!enabled) {
            return null;
        }

        try {
            String embeddingsKey = EMBEDDINGS_KEY_PREFIX + tenantId;
            String responsesKey = RESPONSES_KEY_PREFIX + tenantId;

            List<Object> cachedEmbeddings = redisTemplate.opsForList().range(embeddingsKey, 0, -1);
            if (cachedEmbeddings == null || cachedEmbeddings.isEmpty()) {
                meterRegistry.counter("retrieval.cache.miss").increment();
                return null;
            }

            double bestSimilarity = -1.0;
            int bestIndex = -1;

            for (int i = 0; i < cachedEmbeddings.size(); i++) {
                float[] cached = deserializeEmbedding(cachedEmbeddings.get(i));
                if (cached == null || cached.length != queryEmbedding.length) {
                    continue;
                }
                double similarity = cosineSimilarity(queryEmbedding, cached);
                if (similarity > bestSimilarity) {
                    bestSimilarity = similarity;
                    bestIndex = i;
                }
            }

            if (bestSimilarity >= similarityThreshold && bestIndex >= 0) {
                Object responseObj = redisTemplate.opsForList().index(responsesKey, bestIndex);
                if (responseObj != null) {
                    RetrievalQueryResponse cached = deserializeResponse(responseObj);
                    if (cached != null) {
                        logger.info("Semantic cache HIT — similarity={}, tenant={}", 
                                String.format("%.4f", bestSimilarity), tenantId);
                        meterRegistry.counter("retrieval.cache.hit").increment();
                        return cached;
                    }
                }
            }

            meterRegistry.counter("retrieval.cache.miss").increment();
            return null;
        } catch (Exception e) {
            logger.warn("Semantic cache lookup failed, treating as miss", e);
            meterRegistry.counter("retrieval.cache.miss").increment();
            return null;
        }
    }

    /**
     * Store a query-response pair in the semantic cache.
     */
    public void store(String tenantId, String query, float[] queryEmbedding, RetrievalQueryResponse response) {
        if (!enabled) {
            return;
        }

        try {
            String embeddingsKey = EMBEDDINGS_KEY_PREFIX + tenantId;
            String queriesKey = QUERIES_KEY_PREFIX + tenantId;
            String responsesKey = RESPONSES_KEY_PREFIX + tenantId;

            // Evict oldest entries if at capacity
            Long size = redisTemplate.opsForList().size(embeddingsKey);
            if (size != null && size >= maxEntries) {
                redisTemplate.opsForList().leftPop(embeddingsKey);
                redisTemplate.opsForList().leftPop(queriesKey);
                redisTemplate.opsForList().leftPop(responsesKey);
            }

            String serializedResponse = objectMapper.writeValueAsString(response);
            redisTemplate.opsForList().rightPush(embeddingsKey, serializeEmbedding(queryEmbedding));
            redisTemplate.opsForList().rightPush(queriesKey, query);
            redisTemplate.opsForList().rightPush(responsesKey, serializedResponse);

            redisTemplate.expire(embeddingsKey, ttlSeconds, TimeUnit.SECONDS);
            redisTemplate.expire(queriesKey, ttlSeconds, TimeUnit.SECONDS);
            redisTemplate.expire(responsesKey, ttlSeconds, TimeUnit.SECONDS);

            logger.debug("Semantic cache STORE — tenant={}, query={}", tenantId, 
                    query.substring(0, Math.min(60, query.length())));
        } catch (Exception e) {
            logger.warn("Semantic cache store failed", e);
        }
    }

    private double cosineSimilarity(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    private String serializeEmbedding(float[] embedding) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(embedding[i]);
        }
        return sb.toString();
    }

    private float[] deserializeEmbedding(Object obj) {
        try {
            String str = String.valueOf(obj);
            String[] parts = str.split(",");
            float[] result = new float[parts.length];
            for (int i = 0; i < parts.length; i++) {
                result[i] = Float.parseFloat(parts[i].trim());
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private RetrievalQueryResponse deserializeResponse(Object obj) {
        try {
            String json = String.valueOf(obj);
            return objectMapper.readValue(json, RetrievalQueryResponse.class);
        } catch (Exception e) {
            logger.warn("Failed to deserialize cached response", e);
            return null;
        }
    }
}
