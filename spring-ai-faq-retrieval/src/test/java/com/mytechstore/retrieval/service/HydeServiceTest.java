package com.mytechstore.retrieval.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@DisplayName("HyDE Service Unit Tests")
class HydeServiceTest {

    @Mock
    private ChatClient.Builder chatClientBuilder;
    @Mock
    private ChatClient chatClient;
    @Mock
    private ChatClient.PromptRequest promptRequest;
    @Mock
    private ChatClient.CallPromptResponseSpec callResponse;
    @Mock
    private EmbeddingModel embeddingModel;

    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(chatClientBuilder.build()).thenReturn(chatClient);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("Should return empty embedding when disabled")
    void testDisabledReturnsEmpty() {
        HydeService hydeService = new HydeService(chatClientBuilder, embeddingModel, meterRegistry, false, 150);
        float[] result = hydeService.generateHypothesisEmbedding("What is FAQ?");
        assertEquals(0, result.length);
    }

    @Test
    @DisplayName("Should report enabled state correctly")
    void testEnabledState() {
        HydeService enabled = new HydeService(chatClientBuilder, embeddingModel, meterRegistry, true, 150);
        HydeService disabled = new HydeService(chatClientBuilder, embeddingModel, meterRegistry, false, 150);
        assertTrue(enabled.isEnabled());
        assertFalse(disabled.isEnabled());
    }
}
