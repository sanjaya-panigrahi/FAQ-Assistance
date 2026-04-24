package com.mytechstore.retrieval.service;

import com.mytechstore.retrieval.dto.RetrievalChunk;
import com.mytechstore.retrieval.dto.RetrievalQueryRequest;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class RetrievalPipelineService {

    private static final Logger logger = LoggerFactory.getLogger(RetrievalPipelineService.class);

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "at", "be", "by", "can", "do", "for", "from", "how", "i", "if",
        "in", "is", "it", "me", "my", "of", "on", "or", "the", "to", "we", "what", "when", "where",
        "which", "with", "you", "your"
    );

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final Map<String, float[]> embeddingCache = new ConcurrentHashMap<>();
    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;
    private final RedisTemplate<String, Object> redisTemplate;

    private final String chromaUrl;
    private final String collectionPrefix;
    private final String defaultTenant;
    private final int defaultTopK;
    private final double defaultThreshold;
    private final double weightVector;
    private final double weightLexical;

    public RetrievalPipelineService(
        ChatClient.Builder chatClientBuilder,
        EmbeddingModel embeddingModel,
        ObjectMapper objectMapper,
        RedisTemplate<String, Object> redisTemplate,
        @Value("${retrieval.chroma-url}") String chromaUrl,
        @Value("${retrieval.collection-prefix}") String collectionPrefix,
        @Value("${retrieval.default-tenant}") String defaultTenant,
        @Value("${retrieval.top-k}") int defaultTopK,
        @Value("${retrieval.similarity-threshold}") double defaultThreshold,
        @Value("${retrieval.rerank-weight-vector}") double weightVector,
        @Value("${retrieval.rerank-weight-lexical}") double weightLexical
    ) {
        this.chatClient = chatClientBuilder.build();
        this.embeddingModel = embeddingModel;
        this.objectMapper = objectMapper;
        this.redisTemplate = redisTemplate;
        this.webClient = WebClient.builder().build();
        this.chromaUrl = chromaUrl;
        this.collectionPrefix = collectionPrefix;
        this.defaultTenant = defaultTenant;
        this.defaultTopK = defaultTopK;
        this.defaultThreshold = defaultThreshold;
        this.weightVector = weightVector;
        this.weightLexical = weightLexical;
    }

    public Map<String, Object> health() {
        boolean chromaOk = false;
        try {
            chromaOk = checkHeartbeat("/api/v2/heartbeat") || checkHeartbeat("/api/v1/heartbeat");
        } catch (Exception ignored) {
            chromaOk = false;
        }

        return Map.of(
            "status", chromaOk ? "UP" : "DEGRADED",
            "chromaConnected", chromaOk
        );
    }

    @Cacheable(value = "retrievalCache", key = "#request.tenantId() + ':' + #request.question()")
    @CircuitBreaker(name = "chromaRetrieval", fallbackMethod = "queryFallback")
    @Retry(name = "chromaRetrieval")
    @Bulkhead(name = "chromaRetrieval")
    @RateLimiter(name = "api-limiter")
    public RetrievalQueryResponse query(RetrievalQueryRequest request) {
        // Check for idempotency key
        String idempotencyKey = getIdempotencyKey();
        if (idempotencyKey != null) {
            String cachedKey = "idempotency:" + idempotencyKey;
            Object cachedResponse = redisTemplate.opsForValue().get(cachedKey);
            if (cachedResponse != null) {
                logger.debug("Idempotent request detected - returning cached response for key: {}", idempotencyKey);
                return (RetrievalQueryResponse) cachedResponse;
            }
        }

        long totalStart = System.currentTimeMillis();
        String tenantId = resolveTenantId(request);
        String question = request.question().trim();
        String queryContext = request.queryContext() == null ? "" : request.queryContext().trim();
        int topK = validateTopK(request.topK() == null ? defaultTopK : request.topK());
        double threshold = validateThreshold(request.similarityThreshold() == null ? defaultThreshold : request.similarityThreshold());
        int effectiveTopK = topK;

        logger.debug("Query received - tenant: {}, question length: {}, idempotency_key: {}", 
            tenantId, question.length(), idempotencyKey);

        String transformedQuery = queryContext.isBlank() ? question : question + " " + queryContext;

        long retrievalStart = System.currentTimeMillis();
        List<ChunkCandidate> candidates = hybridRetrieve(tenantId, transformedQuery, Math.max(effectiveTopK * 4, effectiveTopK));
        List<ChunkCandidate> reranked = rerank(candidates, effectiveTopK, threshold);
        int retrievalMs = Math.toIntExact(System.currentTimeMillis() - retrievalStart);
        
        logger.debug("Retrieval completed - candidates: {}, reranked: {}, time: {}ms", 
            candidates.size(), reranked.size(), retrievalMs);

        long generationStart = System.currentTimeMillis();
        String answer = groundedGeneration(question, reranked);
        int generationMs = Math.toIntExact(System.currentTimeMillis() - generationStart);
        
        logger.debug("Generation completed - answer length: {}, time: {}ms", answer.length(), generationMs);

        List<RetrievalChunk> chunks = new ArrayList<>();
        for (ChunkCandidate candidate : reranked) {
            chunks.add(new RetrievalChunk(
                candidate.rank,
                candidate.content,
                candidate.source,
                candidate.chunkNumber,
                candidate.vectorScore,
                candidate.lexicalScore,
                candidate.rerankScore
            ));
        }

        long totalMs = System.currentTimeMillis() - totalStart;
        logger.info("Query pipeline complete - tenant: {}, total time: {}ms (retrieval: {}ms, generation: {}ms)", 
            tenantId, totalMs, retrievalMs, generationMs);

        RetrievalQueryResponse response = new RetrievalQueryResponse(
            tenantId,
            question,
            transformedQuery,
            "query-transform+hybrid-retrieval+rerank+grounded-generation",
            answer,
            chunks.size(),
            !chunks.isEmpty(),
            retrievalMs,
            generationMs,
            chunks
        );

        // Cache idempotent response for 24 hours if idempotency key provided
        if (idempotencyKey != null) {
            String cachedKey = "idempotency:" + idempotencyKey;
            redisTemplate.opsForValue().set(cachedKey, response, java.time.Duration.ofHours(24));
            logger.debug("Cached idempotent response for key: {}", idempotencyKey);
        }

        return response;
    }

    public RetrievalQueryResponse queryFallback(RetrievalQueryRequest request, Exception ex) {
        logger.warn("Circuit breaker fallback triggered - returning cached or error response", ex);
        return new RetrievalQueryResponse(
            request.tenantId() != null ? request.tenantId() : defaultTenant,
            request.question(),
            transformQuery(request.question(), request.queryContext() != null ? request.queryContext() : ""),
            "fallback",
            "Service temporarily unavailable. Please try again later.",
            0,
            false,
            0,
            0,
            Collections.emptyList()
        );
    }

    private String getIdempotencyKey() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                return attrs.getRequest().getHeader("Idempotency-Key");
            }
        } catch (Exception e) {
            logger.debug("Could not retrieve idempotency key from request context", e);
        }
        return null;
    }

    private String resolveTenantId(RetrievalQueryRequest request) {
        String requestedTenant = request.tenantId() == null || request.tenantId().isBlank()
            ? defaultTenant : request.tenantId().trim();
        String authenticatedTenant = getAuthenticatedTenantId();

        if (authenticatedTenant == null || authenticatedTenant.isBlank()) {
            return requestedTenant;
        }

        if (!authenticatedTenant.equals(requestedTenant)) {
            throw new AccessDeniedException("Requested tenant does not match authenticated tenant");
        }

        return authenticatedTenant;
    }

    private String getAuthenticatedTenantId() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                Object tenant = attrs.getRequest().getAttribute("tenantId");
                if (tenant instanceof String tenantId && !tenantId.isBlank()) {
                    return tenantId;
                }
            }
        } catch (Exception e) {
            logger.debug("Could not retrieve authenticated tenant from request context", e);
        }
        return null;
    }

    private int validateTopK(int topK) {
        if (topK < 1 || topK > 20) {
            throw new IllegalArgumentException("topK must be between 1 and 20");
        }
        return topK;
    }

    private double validateThreshold(double threshold) {
        if (threshold < 0.0 || threshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be between 0.0 and 1.0");
        }
        return threshold;
    }

    private String transformQuery(String question, String queryContext) {
        if (queryContext != null && !queryContext.isBlank()) {
            return question + " " + queryContext;
        }
        return question;
    }

    private List<ChunkCandidate> hybridRetrieve(String tenantId, String transformedQuery, int fetchTopK) {
        String collectionName = collectionPrefix + tenantId;
        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null || collectionId.isBlank()) {
            return List.of();
        }

        float[] embedding = embeddingCache.computeIfAbsent(transformedQuery, 
            q -> embeddingModel.embed(q));
        if (embedding == null || embedding.length == 0) {
            return List.of();
        }

        List<Double> embeddingValues = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            embeddingValues.add((double) value);
        }

        Map<String, Object> payload = new HashMap<>();
        payload.put("query_embeddings", List.of(embeddingValues));
        payload.put("n_results", fetchTopK);
        payload.put("include", List.of("documents", "metadatas", "distances"));

        try {
            String response = postJson(chromaUrl + "/api/v1/collections/" + collectionId + "/query", payload);
            Map<String, Object> parsed = objectMapper.readValue(response, new TypeReference<>() {});
            return parseCandidates(transformedQuery, parsed);
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<ChunkCandidate> parseCandidates(String query, Map<String, Object> payload) {
        List<ChunkCandidate> result = new ArrayList<>();
        List<?> documentsOuter = asList(payload.get("documents"));
        List<?> metadatasOuter = asList(payload.get("metadatas"));
        List<?> distancesOuter = asList(payload.get("distances"));

        List<?> documents = documentsOuter.isEmpty() ? List.of() : asList(documentsOuter.get(0));
        List<?> metadatas = metadatasOuter.isEmpty() ? List.of() : asList(metadatasOuter.get(0));
        List<?> distances = distancesOuter.isEmpty() ? List.of() : asList(distancesOuter.get(0));

        for (int i = 0; i < documents.size(); i++) {
            String doc = String.valueOf(documents.get(i));
            double distance = toDouble(i < distances.size() ? distances.get(i) : 1.0, 1.0);
            double vectorScore = clamp(1.0 - distance);
            Map<String, Object> metadata = i < metadatas.size() && metadatas.get(i) instanceof Map<?, ?> m
                ? castMap(m)
                : Map.of();
            double lexicalScore = lexicalScore(query, doc);
            double rerankScore = clamp((weightVector * vectorScore) + (weightLexical * lexicalScore));

            ChunkCandidate candidate = new ChunkCandidate();
            candidate.content = doc;
            candidate.source = metadata.get("document_name") == null ? null : String.valueOf(metadata.get("document_name"));
            candidate.chunkNumber = metadata.get("chunk_number") == null ? null : (int) toDouble(metadata.get("chunk_number"), 0.0);
            candidate.vectorScore = round4(vectorScore);
            candidate.lexicalScore = round4(lexicalScore);
            candidate.rerankScore = round4(rerankScore);
            result.add(candidate);
        }

        return result;
    }

    private List<ChunkCandidate> rerank(List<ChunkCandidate> candidates, int topK, double threshold) {
        List<ChunkCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((ChunkCandidate c) -> c.rerankScore).reversed());

        List<ChunkCandidate> filtered = sorted.stream()
            .filter(c -> c.rerankScore >= threshold)
            .toList();

        List<ChunkCandidate> selected = filtered.isEmpty()
            ? sorted.stream().limit(topK).toList()
            : filtered.stream().limit(topK).toList();

        List<ChunkCandidate> ranked = new ArrayList<>();
        for (int i = 0; i < selected.size(); i++) {
            ChunkCandidate candidate = selected.get(i);
            candidate.rank = i + 1;
            ranked.add(candidate);
        }
        return ranked;
    }

    private String groundedGeneration(String question, List<ChunkCandidate> chunks) {
        if (chunks.isEmpty()) {
            return "No relevant information found for this tenant knowledge base.";
        }

        StringBuilder context = new StringBuilder();
        for (ChunkCandidate chunk : chunks) {
            String src = chunk.source == null ? "unknown-source" : chunk.source;
            context.append("[").append(chunk.rank).append("] (")
                .append(src)
                .append(") ")
                .append(chunk.content)
                .append("\n\n");
        }

        String prompt = "You are a FAQ assistant. Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
            + "Question: " + question + "\n\n"
            + "Context:\n" + context;

        String response = chatClient.prompt().user(prompt).call().content();
        return response == null ? "No answer generated." : response;
    }

    private String resolveCollectionId(String collectionName) {
        try {
            String url = chromaUrl + "/api/v1/collections?name=" + collectionName;
            String response = webClient.get()
                .uri(url)
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
            if (response == null || response.isBlank()) {
                return null;
            }

            Object payload = objectMapper.readValue(response, Object.class);
            if (payload instanceof Map<?, ?> map) {
                Map<String, Object> data = castMap(map);
                if (data.get("id") != null) {
                    return String.valueOf(data.get("id"));
                }
                if (data.get("collections") instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> col) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> c) {
                            Map<String, Object> candidate = castMap(c);
                            Object name = candidate.get("name");
                            if (name != null && collectionName.equals(String.valueOf(name))) {
                                Object id = candidate.get("id");
                                return id == null ? null : String.valueOf(id);
                            }
                        }
                    }
                }
            }
            if (payload instanceof List<?> list && !list.isEmpty()) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> c) {
                        Map<String, Object> candidate = castMap(c);
                        Object name = candidate.get("name");
                        if (name != null && collectionName.equals(String.valueOf(name))) {
                            Object id = candidate.get("id");
                            return id == null ? null : String.valueOf(id);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private boolean checkHeartbeat(String path) throws IOException, InterruptedException {
        String response = webClient.get()
            .uri(chromaUrl + path)
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(5));
        return response != null;
    }

    private String postJson(String url, Object payload) throws IOException, InterruptedException {
        String response = webClient.post()
            .uri(url)
            .header("Content-Type", "application/json")
            .bodyValue(payload)
            .retrieve()
            .bodyToMono(String.class)
            .block(Duration.ofSeconds(20));

        if (response == null) {
            throw new IllegalStateException("ChromaDB query failed: empty response");
        }
        return response;
    }

    private double lexicalScore(String query, String document) {
        Set<String> queryTokens = new LinkedHashSet<>(Arrays.asList(normalize(query).split(" ")));
        queryTokens.removeIf(token -> token.isBlank() || STOP_WORDS.contains(token));

        String haystack = normalize(document);
        if (queryTokens.isEmpty() || haystack.isBlank()) {
            return 0.0;
        }

        double score = 0.0;
        for (String token : queryTokens) {
            if (haystack.contains(token)) {
                score += 1.0;
            }
        }

        double denom = Math.max(1.0, queryTokens.size());
        return clamp(score / denom);
    }

    private String normalize(String input) {
        String safe = input == null ? "" : input.toLowerCase(Locale.ROOT);
        safe = NON_ALNUM.matcher(safe).replaceAll(" ");
        safe = MULTI_SPACE.matcher(safe).replaceAll(" ");
        return safe.trim();
    }

    private List<?> asList(Object value) {
        return value instanceof List<?> list ? list : Collections.emptyList();
    }

    private double toDouble(Object value, double defaultValue) {
        if (value instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static class ChunkCandidate {
        int rank;
        String content;
        String source;
        Integer chunkNumber;
        double vectorScore;
        double lexicalScore;
        double rerankScore;
    }
}
