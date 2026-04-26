package com.mytechstore.retrieval.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Cross-encoder reranker that calls the BGE-reranker-v2 sidecar.
 * Falls back to weighted scoring when the sidecar is unavailable.
 */
@Service
public class CrossEncoderReranker {

    private static final Logger logger = LoggerFactory.getLogger(CrossEncoderReranker.class);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String rerankerUrl;
    private final Timer rerankerTimer;
    private final MeterRegistry meterRegistry;

    public CrossEncoderReranker(
            WebClient webClient,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${reranker.enabled:false}") boolean enabled,
            @Value("${reranker.url:http://reranker-service:8100}") String rerankerUrl) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
        this.enabled = enabled;
        this.rerankerUrl = rerankerUrl;
        this.rerankerTimer = Timer.builder("retrieval.reranker.latency")
                .description("Cross-encoder reranker latency")
                .register(meterRegistry);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Rerank documents using the cross-encoder sidecar.
     * Returns scored results with cross-encoder scores replacing rerankScore.
     */
    @CircuitBreaker(name = "rerankerService", fallbackMethod = "rerankFallback")
    public List<ScoredResult> rerank(String query, List<String> documents, int topK) {
        if (!enabled || documents.isEmpty()) {
            return List.of();
        }

        return rerankerTimer.record(() -> {
            Map<String, Object> payload = Map.of(
                    "query", query,
                    "documents", documents,
                    "top_k", topK
            );

            String response = webClient.post()
                    .uri(rerankerUrl + "/rerank")
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (response == null) {
                logger.warn("Reranker returned null response");
                return List.<ScoredResult>of();
            }

            return parseResponse(response);
        });
    }

    @SuppressWarnings("unused")
    private List<ScoredResult> rerankFallback(String query, List<String> documents, int topK, Exception ex) {
        logger.warn("Cross-encoder reranker unavailable, falling back to weighted scoring: {}", ex.getMessage());
        meterRegistry.counter("retrieval.reranker.fallback").increment();
        return List.of(); // empty signals caller to use weighted fallback
    }

    private List<ScoredResult> parseResponse(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});
            Object resultsObj = parsed.get("results");
            if (!(resultsObj instanceof List<?> resultsList)) {
                return List.of();
            }

            List<ScoredResult> results = new ArrayList<>();
            for (Object item : resultsList) {
                if (item instanceof Map<?, ?> map) {
                    int index = ((Number) map.get("index")).intValue();
                    double score = ((Number) map.get("score")).doubleValue();
                    String content = String.valueOf(map.get("content"));
                    results.add(new ScoredResult(index, content, score));
                }
            }
            results.sort(Comparator.comparingDouble(ScoredResult::score).reversed());
            return results;
        } catch (Exception e) {
            logger.error("Failed to parse reranker response", e);
            return List.of();
        }
    }

    public record ScoredResult(int originalIndex, String content, double score) {}
}
