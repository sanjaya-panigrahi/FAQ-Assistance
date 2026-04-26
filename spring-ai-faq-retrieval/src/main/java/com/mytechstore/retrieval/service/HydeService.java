package com.mytechstore.retrieval.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * HyDE — Hypothetical Document Embeddings.
 * Generates a hypothetical FAQ answer via LLM, then embeds it
 * to produce a second query vector for dual-search.
 */
@Service
public class HydeService {

    private static final Logger logger = LoggerFactory.getLogger(HydeService.class);

    private static final String HYDE_SYSTEM_PROMPT =
            "You are an FAQ knowledge-base assistant. "
            + "Given the user's question, write a short, factual answer (2-4 sentences) "
            + "as if it existed in a product FAQ document. "
            + "Do not say 'I don't know'. Always produce a plausible answer.";

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final boolean enabled;
    private final int maxTokens;
    private final Timer hydeTimer;

    public HydeService(
            ChatClient.Builder chatClientBuilder,
            EmbeddingModel embeddingModel,
            MeterRegistry meterRegistry,
            @Value("${hyde.enabled:false}") boolean enabled,
            @Value("${hyde.max-tokens:150}") int maxTokens) {
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
        this.enabled = enabled;
        this.maxTokens = maxTokens;
        this.hydeTimer = Timer.builder("retrieval.hyde.latency")
                .description("HyDE generation + embedding latency")
                .register(meterRegistry);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Generate a hypothetical document for the query and return its embedding.
     */
    public float[] generateHypothesisEmbedding(String query) {
        if (!enabled) {
            return new float[0];
        }

        return hydeTimer.record(() -> {
            try {
                String hypothesis = chatClient.prompt()
                        .system(HYDE_SYSTEM_PROMPT)
                        .user(query)
                        .call()
                        .content();

                if (hypothesis == null || hypothesis.isBlank()) {
                    logger.warn("HyDE generated empty hypothesis for query: {}", query);
                    return new float[0];
                }

                logger.debug("HyDE hypothesis ({}chars): {}", hypothesis.length(),
                        hypothesis.substring(0, Math.min(100, hypothesis.length())));

                return embeddingModel.embed(hypothesis);
            } catch (Exception e) {
                logger.error("HyDE generation failed, continuing without hypothesis embedding", e);
                return new float[0];
            }
        });
    }
}
