package com.mytechstore.graphrag.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.time.Duration;
import java.util.HashMap;

import com.mytechstore.graphrag.dto.RagResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class GraphPipelineService {

        private static final Set<String> STOP_WORDS = Set.of(
                        "a", "an", "and", "are", "at", "be", "by", "can", "do", "for", "from", "how", "i", "if",
                        "in", "is", "it", "me", "my", "of", "on", "or", "the", "to", "we", "what", "when", "where",
                        "which", "with", "you", "your"
        );

    private final VectorStore vectorStore;
    private final Neo4jClient neo4jClient;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);
        private volatile List<Document> faqEntries = List.of();
        private volatile List<String> indexedVectorDocIds = List.of();
        private volatile String lastFaqDigest = "";
        private final EmbeddingModel embeddingModel;
        private final String chromaUrl;
        private final String collectionPrefix;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final WebClient webClient;
        private final int defaultTopK;
    private final AnalyticsReporter analyticsReporter;

    public GraphPipelineService(VectorStore vectorStore,
                                Neo4jClient neo4jClient,
                                ChatClient chatClient,
                                @Value("${faq.source-file}") String sourceFile,
                                EmbeddingModel embeddingModel,
                                @Value("${chroma.url:http://chroma-faq:8000}") String chromaUrl,
                                @Value("${chroma.collection-prefix:faq_}") String collectionPrefix,
                                @Value("${retrieval.top-k:6}") int defaultTopK,
                                WebClient webClient,
                                AnalyticsReporter analyticsReporter) {
        this.vectorStore = vectorStore;
        this.neo4jClient = neo4jClient;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
        this.embeddingModel = embeddingModel;
        this.chromaUrl = chromaUrl;
        this.collectionPrefix = collectionPrefix;
        this.defaultTopK = defaultTopK;
        this.webClient = webClient;
        this.analyticsReporter = analyticsReporter;
    }

    @CacheEvict(value = {"graphAnswers", "graphChroma"}, allEntries = true)
        public synchronized String rebuildIndex() {
        List<Document> docs = parseFaqDocuments();

                String currentDigest = computeDigest(docs);
                if (indexed.get() && currentDigest.equals(lastFaqDigest)) {
                        return "Index already up to date (" + docs.size() + " FAQ entries)";
                }

                if (!indexedVectorDocIds.isEmpty()) {
                        vectorStore.delete(indexedVectorDocIds);
                }
        vectorStore.add(docs);
                indexedVectorDocIds = docs.stream().map(Document::getId).toList();
        faqEntries = docs;

        neo4jClient.query("MATCH (n:FaqChunk) DETACH DELETE n").run();
        List<Map<String, Object>> rows = docs.stream()
                .map(d -> Map.<String, Object>of(
                        "id", UUID.randomUUID().toString(),
                        "text", d.getText(),
                        "section", String.valueOf(d.getMetadata().getOrDefault("section", "General FAQ")),
                        "question", String.valueOf(d.getMetadata().getOrDefault("question", "")),
                        "answer", String.valueOf(d.getMetadata().getOrDefault("answer", ""))
                ))
                .toList();
        neo4jClient.query("UNWIND $rows AS row CREATE (:FaqChunk {id: row.id, text: row.text, section: row.section, question: row.question, answer: row.answer})")
                .bind(rows).to("rows").run();

        indexed.set(true);
                lastFaqDigest = currentDigest;
        return "Indexed " + docs.size() + " FAQ entries into vector and graph layers";
    }

        @Cacheable(value = "graphAnswers", key = "#customerId + ':' + #question")
        public RagResponse ask(String question, String customerId) {
                List<String> chromaChunks = queryChroma(customerId, question, defaultTopK);
                String vectorContext;
                int vectorCount;
                if (!chromaChunks.isEmpty()) {
                        vectorContext = String.join("\n\n", chromaChunks);
                        vectorCount = chromaChunks.size();
                } else {
                        if (!indexed.get()) {
                                rebuildIndex();
                        }
                        List<Document> vectorHits = vectorStore.similaritySearch(
                                        SearchRequest.builder().query(question).topK(defaultTopK).build());
                        vectorContext = vectorHits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
                        vectorCount = vectorHits.size();
                }

                List<String> tokens = extractSearchTokens(question);
        List<String> graphFacts = neo4jClient.query(
                                                "UNWIND $tokens AS token "
                                                                + "MATCH (f:FaqChunk) "
                                                                + "WHERE toLower(f.text) CONTAINS token "
                                                                + "RETURN f.text AS text, count(*) AS score "
                                                                + "ORDER BY score DESC "
                                                                + "LIMIT 3")
                                .bind(tokens.isEmpty() ? List.of(normalize(question)) : tokens).to("tokens")
                .fetch().all().stream()
                .map(row -> row.get("text").toString())
                                .distinct()
                .toList();

        String graphContext = String.join("\n", graphFacts);
        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". "
            + "Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
                + "Vector context:\n" + vectorContext + "\n\n"
                + "Graph facts:\n" + graphContext + "\n\n"
                + "Question: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        RagResponse response = new RagResponse(answer, vectorCount, graphFacts.size(), "chroma-direct+neo4j-graph",
                "springai-neo4j-graph");
        analyticsReporter.postEvent(question, answer, customerId != null ? customerId : "default",
                "neo4j-graph", "chroma-direct+neo4j-graph", 0,
                vectorContext + "\n\n" + graphContext);
        return response;
    }

        private List<String> queryChroma(String customerId, String question, int topK) {
                if (customerId == null || customerId.isBlank()) return List.of();
                try {
                        String collectionName = collectionPrefix + customerId.trim();
            
                        // Get collection ID
                        String collectionResponse = webClient.get()
                                        .uri(chromaUrl + "/api/v1/collections/" + collectionName)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .block(Duration.ofSeconds(5));
            
                        if (collectionResponse == null) return List.of();
            
                        @SuppressWarnings("unchecked")
                        Map<String, Object> coll = objectMapper.readValue(collectionResponse, Map.class);
                        String collectionId = String.valueOf(coll.get("id"));

                        float[] embArr = embeddingModel.embed(question);
                        List<Float> embedding = new ArrayList<>();
                        for (float f : embArr) embedding.add(f);

                        Map<String, Object> queryPayload = new HashMap<>();
                        queryPayload.put("query_embeddings", List.of(embedding));
                        queryPayload.put("n_results", topK);
                        queryPayload.put("include", List.of("documents"));

                        String queryResponse = webClient.post()
                                        .uri(chromaUrl + "/api/v1/collections/" + collectionId + "/query")
                                        .header("Content-Type", "application/json")
                                        .bodyValue(queryPayload)
                                        .retrieve()
                                        .bodyToMono(String.class)
                                        .block(Duration.ofSeconds(30));
            
                        if (queryResponse == null) return List.of();

                        @SuppressWarnings("unchecked")
                        Map<String, Object> result = objectMapper.readValue(queryResponse, Map.class);
                        Object docsObj = result.get("documents");
                        if (docsObj instanceof List<?> outer && !((List<?>) outer).isEmpty()
                                        && outer.get(0) instanceof List<?> inner) {
                                return inner.stream().filter(o -> o != null).map(Object::toString).toList();
                        }
                        return List.of();
                } catch (Exception e) {
                        return List.of();
                }
        }

        private List<Document> parseFaqDocuments() {
                Path path = Path.of(sourceFile);
                if (!Files.exists(path)) {
                        throw new IllegalStateException("FAQ source file not found: " + sourceFile);
                }

                try {
                        List<String> lines = Files.readAllLines(path);
                        List<Document> documents = new ArrayList<>();
                        String currentSection = "General FAQ";
                        String currentQuestion = null;
                        StringBuilder answerBuilder = new StringBuilder();
                        int faqId = 0;

                        for (String rawLine : lines) {
                                String trimmed = rawLine == null ? "" : rawLine.trim();
                                if (trimmed.startsWith("## ")) {
                                        faqId = addFaqDocument(documents, faqId, currentSection, currentQuestion, answerBuilder);
                                        currentSection = trimmed.substring(3).trim();
                                        currentQuestion = null;
                                        answerBuilder.setLength(0);
                                        continue;
                                }
                                if (trimmed.matches("\\d+\\.\\s+\\*\\*.*\\*\\*")) {
                                        faqId = addFaqDocument(documents, faqId, currentSection, currentQuestion, answerBuilder);
                                        currentQuestion = trimmed.replaceFirst("^\\d+\\.\\s+\\*\\*(.*?)\\*\\*$", "$1").trim();
                                        answerBuilder.setLength(0);
                                        continue;
                                }
                                if (currentQuestion != null && !trimmed.isEmpty()) {
                                        if (answerBuilder.length() > 0) {
                                                answerBuilder.append(' ');
                                        }
                                        answerBuilder.append(trimmed);
                                }
                        }

                        addFaqDocument(documents, faqId, currentSection, currentQuestion, answerBuilder);
                        if (documents.isEmpty()) {
                                throw new IllegalStateException("No FAQ entries found in: " + sourceFile);
                        }
                        return documents;
                } catch (IOException ex) {
                        throw new IllegalStateException("Failed to read FAQ source file: " + sourceFile, ex);
                }
        }

                private String computeDigest(List<Document> docs) {
                                return Integer.toHexString(docs.stream()
                                                                .map(doc -> {
                                                                                Object section = doc.getMetadata().getOrDefault("section", "");
                                                                                Object question = doc.getMetadata().getOrDefault("question", "");
                                                                                Object answer = doc.getMetadata().getOrDefault("answer", "");
                                                                                return section + "|" + question + "|" + answer;
                                                                })
                                                                .collect(Collectors.joining("\n"))
                                                                .hashCode());
                }

        private int addFaqDocument(List<Document> documents, int faqId, String section, String question, StringBuilder answerBuilder) {
                if (question == null || question.isBlank()) {
                        return faqId;
                }
                String answer = answerBuilder.toString().trim();
                if (answer.isBlank()) {
                        return faqId;
                }

                int nextFaqId = faqId + 1;
                String text = "Section: " + section + "\nQuestion: " + question + "\nAnswer: " + answer;
                documents.add(new Document(text, Map.of(
                                "faqId", nextFaqId,
                                "section", section,
                                "question", question,
                                "answer", answer
                )));
                return nextFaqId;
        }

        private List<String> extractSearchTokens(String question) {
                return Arrays.stream(normalize(question).split("\\s+"))
                                .filter(token -> !token.isBlank())
                                .filter(token -> !STOP_WORDS.contains(token))
                                .limit(6)
                                .collect(Collectors.toCollection(ArrayList::new));
        }

        private String normalize(String value) {
                return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        }
}