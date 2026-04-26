package com.mytechstore.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("SemanticCacheService Unit Tests")
class SemanticCacheServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    @Mock
    private ListOperations<String, Object> listOps;

    private MeterRegistry meterRegistry;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForList()).thenReturn(listOps);
        meterRegistry = new SimpleMeterRegistry();
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("Should return null when disabled")
    void testDisabledReturnsNull() {
        SemanticCacheService cache = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                false, 0.95, 3600, 5000);
        assertNull(cache.lookup("tenant", "query", new float[]{0.1f, 0.2f}));
    }

    @Test
    @DisplayName("Should return null on cache miss (empty cache)")
    void testCacheMissOnEmptyCache() {
        SemanticCacheService cache = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                true, 0.95, 3600, 5000);

        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(null);

        RetrievalQueryResponse result = cache.lookup("tenant", "query", new float[]{0.1f, 0.2f});
        assertNull(result);
    }

    @Test
    @DisplayName("Should return null when similarity below threshold")
    void testCacheMissBelowThreshold() {
        SemanticCacheService cache = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                true, 0.95, 3600, 5000);

        // Cached embedding is very different from query
        when(listOps.range(anyString(), eq(0L), eq(-1L)))
                .thenReturn(List.of("0.9,0.9,0.9"));

        RetrievalQueryResponse result = cache.lookup("tenant", "query", new float[]{0.1f, 0.2f, 0.3f});
        assertNull(result);
    }

    @Test
    @DisplayName("Should report enabled state correctly")
    void testEnabledState() {
        SemanticCacheService enabled = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                true, 0.95, 3600, 5000);
        SemanticCacheService disabled = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                false, 0.95, 3600, 5000);
        assertTrue(enabled.isEnabled());
        assertFalse(disabled.isEnabled());
    }

    @Test
    @DisplayName("Should not store when disabled")
    void testStoreWhenDisabled() {
        SemanticCacheService cache = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                false, 0.95, 3600, 5000);

        RetrievalQueryResponse response = new RetrievalQueryResponse(
                "tenant", "q", "tq", "strategy", "answer",
                1, true, 100, 50, Collections.emptyList(),
                false, false, "weighted");

        cache.store("tenant", "query", new float[]{0.1f}, response);
        verifyNoInteractions(listOps);
    }

    @Test
    @DisplayName("Should increment cache miss counter on miss")
    void testCacheMissCounter() {
        SemanticCacheService cache = new SemanticCacheService(
                embeddingModel, redisTemplate, objectMapper, meterRegistry,
                true, 0.95, 3600, 5000);

        when(listOps.range(anyString(), eq(0L), eq(-1L))).thenReturn(Collections.emptyList());

        cache.lookup("tenant", "query", new float[]{0.1f});

        double missCount = meterRegistry.counter("retrieval.cache.miss").count();
        assertEquals(1.0, missCount, 0.001);
    }
}
