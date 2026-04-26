package com.mytechstore.retrieval.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CrossEncoderReranker Unit Tests")
class CrossEncoderRerankerTest {

    @Mock
    private WebClient webClient;
    @Mock
    private ObjectMapper objectMapper;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("Should return empty list when disabled")
    void testDisabledReturnsEmpty() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(
                webClient, new ObjectMapper(), meterRegistry, false, "http://localhost:8100");
        List<CrossEncoderReranker.ScoredResult> result = reranker.rerank("query", List.of("doc1"), 6);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should return empty list for empty documents")
    void testEmptyDocumentsReturnsEmpty() {
        CrossEncoderReranker reranker = new CrossEncoderReranker(
                webClient, new ObjectMapper(), meterRegistry, true, "http://localhost:8100");
        List<CrossEncoderReranker.ScoredResult> result = reranker.rerank("query", List.of(), 6);
        assertTrue(result.isEmpty());
    }

    @Test
    @DisplayName("Should report enabled state correctly")
    void testEnabledState() {
        CrossEncoderReranker enabled = new CrossEncoderReranker(
                webClient, new ObjectMapper(), meterRegistry, true, "http://localhost:8100");
        CrossEncoderReranker disabled = new CrossEncoderReranker(
                webClient, new ObjectMapper(), meterRegistry, false, "http://localhost:8100");
        assertTrue(enabled.isEnabled());
        assertFalse(disabled.isEnabled());
    }

    @Test
    @DisplayName("ScoredResult record should hold correct values")
    void testScoredResultRecord() {
        CrossEncoderReranker.ScoredResult result = new CrossEncoderReranker.ScoredResult(0, "doc content", 0.95);
        assertEquals(0, result.originalIndex());
        assertEquals("doc content", result.content());
        assertEquals(0.95, result.score(), 0.001);
    }
}
