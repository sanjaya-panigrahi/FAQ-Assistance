package com.mytechstore.unified.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed embedding cache wrapping the underlying EmbeddingModel.
 * Mirrors Python's CachedOpenAIEmbeddings to avoid redundant OpenAI calls.
 */
@Component
public class CachedEmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(CachedEmbeddingModel.class);
    private static final Duration TTL = Duration.ofHours(24);

    private final EmbeddingModel delegate;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public CachedEmbeddingModel(EmbeddingModel delegate, StringRedisTemplate redis) {
        this.delegate = delegate;
        this.redis = redis;
    }

    public float[] embed(String text) {
        String key = cacheKey(text);
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null) {
                return mapper.readValue(cached, float[].class);
            }
        } catch (Exception e) {
            log.debug("Embedding cache read failed: {}", e.getMessage());
        }

        float[] result = delegate.embed(text);

        try {
            redis.opsForValue().set(key, mapper.writeValueAsString(result), TTL);
        } catch (JsonProcessingException e) {
            log.debug("Embedding cache write failed: {}", e.getMessage());
        }
        return result;
    }

    private String cacheKey(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return "emb:" + HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return "emb:" + text.hashCode();
        }
    }
}
