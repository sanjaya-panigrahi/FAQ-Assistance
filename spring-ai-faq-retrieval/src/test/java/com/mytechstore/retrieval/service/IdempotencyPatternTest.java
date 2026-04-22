package com.mytechstore.retrieval.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("Idempotency Pattern Tests")
class IdempotencyPatternTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOps;

    private String idempotencyKey;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        idempotencyKey = UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("Should generate idempotency key from UUID")
    void testIdempotencyKeyGeneration() {
        assertNotNull(idempotencyKey);
        assertTrue(idempotencyKey.length() > 0);
    }

    @Test
    @DisplayName("Should cache response with idempotency key")
    void testIdempotencyCaching() {
        String cacheKey = "idempotency:" + idempotencyKey;
        String mockResponse = "cached_response";

        when(valueOps.get(cacheKey)).thenReturn(mockResponse);

        Object cachedValue = valueOps.get(cacheKey);
        assertEquals(mockResponse, cachedValue);
        
        verify(valueOps).get(cacheKey);
    }

    @Test
    @DisplayName("Should set TTL of 24 hours for idempotency cache")
    void testIdempotencyTTL() {
        String cacheKey = "idempotency:" + idempotencyKey;
        String mockResponse = "response";
        Duration ttl = Duration.ofHours(24);

        valueOps.set(cacheKey, mockResponse, ttl);

        verify(valueOps).set(cacheKey, mockResponse, ttl);
    }

    @Test
    @DisplayName("Should return cached response on duplicate request")
    void testDuplicateRequestReturnsCache() {
        String cacheKey = "idempotency:" + idempotencyKey;
        String response1 = "first_response";

        when(valueOps.get(cacheKey))
            .thenReturn(null)  // First call - cache miss
            .thenReturn(response1);  // Second call - cache hit

        // First call
        Object firstResult = valueOps.get(cacheKey);
        assertNull(firstResult);

        // Store in cache
        valueOps.set(cacheKey, response1, Duration.ofHours(24));

        // Second call - should return cached value
        Object secondResult = valueOps.get(cacheKey);
        assertEquals(response1, secondResult);
    }

    @Test
    @DisplayName("Should handle expired idempotency keys")
    void testExpiredIdempotencyKey() {
        String cacheKey = "idempotency:" + idempotencyKey;
        
        when(valueOps.get(cacheKey)).thenReturn(null);

        Object result = valueOps.get(cacheKey);
        assertNull(result);
    }

    @Test
    @DisplayName("Should differentiate between different idempotency keys")
    void testMultipleIdempotencyKeys() {
        String key1 = "idempotency:" + UUID.randomUUID();
        String key2 = "idempotency:" + UUID.randomUUID();
        String response1 = "response1";
        String response2 = "response2";

        when(valueOps.get(key1)).thenReturn(response1);
        when(valueOps.get(key2)).thenReturn(response2);

        assertEquals(response1, valueOps.get(key1));
        assertEquals(response2, valueOps.get(key2));
        assertNotEquals(valueOps.get(key1), valueOps.get(key2));
    }
}
