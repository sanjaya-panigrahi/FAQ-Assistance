package com.mytechstore.retrieval.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SemanticIntentMatcher Unit Tests")
class SemanticIntentMatcherTest {

    @Test
    @DisplayName("Falls back to lexical product availability intent when embedding fails")
    void lexicalFallbackForProductAvailability() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding unavailable"));

        SemanticIntentMatcher matcher = new SemanticIntentMatcher(embeddingModel);
        SemanticIntentMatcher.IntentMatch match = matcher.match("do you sell new product or refurbish product");

        assertEquals("product_availability", match.name());
    }

    @Test
    @DisplayName("Falls back to lexical logistics intent when embedding fails")
    void lexicalFallbackForLogistics() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("embedding unavailable"));

        SemanticIntentMatcher matcher = new SemanticIntentMatcher(embeddingModel);
        SemanticIntentMatcher.IntentMatch match = matcher.match("how long does shipping delivery take");

        assertEquals("logistics", match.name());
    }
}
