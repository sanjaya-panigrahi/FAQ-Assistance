package com.mytechstore.retrieval.service;

import com.mytechstore.retrieval.dto.RetrievalChunk;
import com.mytechstore.retrieval.dto.RetrievalQueryRequest;
import com.mytechstore.retrieval.dto.RetrievalQueryResponse;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
import java.util.regex.Pattern;

@Service
public class RetrievalPipelineService {

    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "at", "be", "by", "can", "do", "for", "from", "how", "i", "if",
        "in", "is", "it", "me", "my", "of", "on", "or", "the", "to", "we", "what", "when", "where",
        "which", "with", "you", "your"
    );

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

    private final ChatClient chatClient;
    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

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
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
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
            "pipeline", "query-transformation -> hybrid-retrieval -> reranking -> generation-grounding",
            "chromaConnected", chromaOk,
            "chromaUrl", chromaUrl
        );
    }

    public RetrievalQueryResponse query(RetrievalQueryRequest request) {
        String tenantId = request.tenantId() == null || request.tenantId().isBlank()
            ? defaultTenant : request.tenantId().trim();
        String question = request.question().trim();
        String queryContext = request.queryContext() == null ? "" : request.queryContext().trim();
        int topK = request.topK() == null ? defaultTopK : request.topK();
        double threshold = request.similarityThreshold() == null ? defaultThreshold : request.similarityThreshold();

        String transformedQuery = transformQuery(question, queryContext);

        long retrievalStart = System.currentTimeMillis();
        List<ChunkCandidate> candidates = hybridRetrieve(tenantId, transformedQuery, Math.max(topK * 4, topK));
        List<ChunkCandidate> reranked = rerank(candidates, topK, threshold);
        int retrievalMs = Math.toIntExact(System.currentTimeMillis() - retrievalStart);

        long generationStart = System.currentTimeMillis();
        String answer = groundedGeneration(question, reranked);
        int generationMs = Math.toIntExact(System.currentTimeMillis() - generationStart);

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

        return new RetrievalQueryResponse(
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
    }

    private String transformQuery(String question, String queryContext) {
        String q = question.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> expansions = new LinkedHashSet<>();

        if (q.contains("return") || q.contains("refund") || q.contains("exchange")) {
            expansions.add("return policy");
            expansions.add("refund eligibility");
            expansions.add("defective items");
            expansions.add("unopened products");
        }
        if (q.contains("shipping") || q.contains("delivery")) {
            expansions.add("shipping zones");
            expansions.add("delivery timelines");
            expansions.add("international shipping");
        }
        if (q.contains("warranty") || q.contains("guarantee")) {
            expansions.add("warranty coverage");
            expansions.add("replacement process");
        }

        String transformed = question;
        if (!expansions.isEmpty()) {
            transformed = transformed + " | related: " + String.join(", ", expansions);
        }
        if (!queryContext.isBlank()) {
            transformed = transformed + " | context: " + queryContext;
        }
        return transformed;
    }

    private List<ChunkCandidate> hybridRetrieve(String tenantId, String transformedQuery, int fetchTopK) {
        String collectionName = collectionPrefix + tenantId;
        String collectionId = resolveCollectionId(collectionName);
        if (collectionId == null || collectionId.isBlank()) {
            return List.of();
        }

        float[] embedding = embeddingModel.embed(transformedQuery);
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

        String prompt = "You are a grounded FAQ assistant. Use only the context. Cite evidence tags like [1], [2]. "
            + "If context is insufficient, say so clearly.\n\n"
            + "Question: " + question + "\n\n"
            + "Context:\n" + context;

        String response = chatClient.prompt().user(prompt).call().content();
        return response == null ? "No answer generated." : response;
    }

    private String resolveCollectionId(String collectionName) {
        try {
            String url = chromaUrl + "/api/v1/collections?name=" + collectionName;
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }

            Object payload = objectMapper.readValue(response.body(), Object.class);
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
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(chromaUrl + path))
            .timeout(Duration.ofSeconds(5))
            .GET()
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.statusCode() >= 200 && response.statusCode() < 300;
    }

    private String postJson(String url, Object payload) throws IOException, InterruptedException {
        String requestBody = objectMapper.writeValueAsString(payload);
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("ChromaDB query failed: " + response.statusCode());
        }
        return response.body();
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
