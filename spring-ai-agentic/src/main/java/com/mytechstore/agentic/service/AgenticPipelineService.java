package com.mytechstore.agentic.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import com.mytechstore.shared.registry.FAQPatternRegistry;

import com.mytechstore.agentic.dto.RagResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class AgenticPipelineService {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "at", "be", "by", "can", "do", "for", "from", "how", "i", "if",
            "in", "is", "it", "me", "my", "of", "on", "or", "the", "to", "we", "what", "when", "where",
            "which", "with", "you", "your"
    );

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);
    private volatile List<Document> faqEntries = List.of();
    private final EmbeddingModel embeddingModel;
    private final String chromaUrl;
    private final String collectionPrefix;
    private final FAQPatternRegistry patternRegistry = new FAQPatternRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();


    public AgenticPipelineService(VectorStore vectorStore, ChatClient chatClient,
                                  @Value("${faq.source-file}") String sourceFile,
                                  EmbeddingModel embeddingModel,
                                  @Value("${chroma.url:http://chroma-faq:8000}") String chromaUrl,
                                  @Value("${chroma.collection-prefix:faq_}") String collectionPrefix) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
        this.embeddingModel = embeddingModel;
        this.chromaUrl = chromaUrl;
        this.collectionPrefix = collectionPrefix;
    }

    public synchronized String rebuildIndex() {
        List<Document> docs = parseFaqDocuments();
        vectorStore.add(docs);
        faqEntries = docs;
        indexed.set(true);
        return "Indexed " + docs.size() + " FAQ entries from " + sourceFile;
    }

    public RagResponse ask(String question, String customerId) {
        // Agent-style orchestration: planner step chooses how broad retrieval should be.
        int topK = question.toLowerCase().contains("compare") ? 6 : 4;
        String retrievalQuery = expandQuery(question);

        List<String> chromaChunks = queryChroma(customerId, retrievalQuery, topK);
        String context;
        int chunksUsed;
        if (!chromaChunks.isEmpty()) {
            context = String.join("\n\n", chromaChunks);
            chunksUsed = chromaChunks.size();
        } else {
            if (!indexed.get()) {
                rebuildIndex();
            }
            List<Document> retrieved = retrieveRelevantDocuments(retrievalQuery, topK);
            context = retrieved.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = retrieved.size();
        }

        String patternId = patternRegistry.classifyQuestion(question);
        String structuredAnswer = patternRegistry.extractFaqAnswer(question, context);
        if ("return_policy".equals(patternId) && structuredAnswer == null) {
            List<String> policyChunks = queryChroma(
                customerId,
                "What is your return policy? returns unopened items defective items",
                topK
            );
            if (!policyChunks.isEmpty()) {
                LinkedHashSet<String> mergedChunks = new LinkedHashSet<>();
                mergedChunks.addAll(chromaChunks);
                mergedChunks.addAll(policyChunks);
                context = String.join("\n\n", mergedChunks);
                chunksUsed = Math.max(chunksUsed, mergedChunks.size());
                structuredAnswer = patternRegistry.extractFaqAnswer(question, context);
            }
        }

        if (structuredAnswer == null && patternId != null) {
            if (faqEntries.isEmpty()) {
                faqEntries = parseFaqDocuments();
            }
            structuredAnswer = patternRegistry.extractFaqAnswer(
                question,
                faqEntries.stream().map(Document::getText).collect(Collectors.joining("\n\n"))
            );
        }
        if (structuredAnswer != null && !structuredAnswer.isBlank()) {
            return new RagResponse(
                structuredAnswer,
                "pattern-registry+structured-extraction",
                chunksUsed,
                "springai-agent-orchestration"
            );
        }

        String prompt = "You are MyTechStore FAQ assistant. Use only the context. "
            + "If the context contains a general policy that applies to the question, answer with that policy directly. "
            + "Do not add generic caveats such as 'check product page' unless that caveat is explicitly stated in the context. "
            + "If context is missing, say you do not have enough policy data.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;

        String answer = chatClient.prompt().user(prompt).call().content();
        return new RagResponse(answer, "chroma-direct+agent-plan", chunksUsed, "springai-agent-orchestration");
    }

    private List<String> queryChroma(String customerId, String question, int topK) {
        if (customerId == null || customerId.isBlank()) return List.of();
        try {
            String collectionName = collectionPrefix + customerId.trim();
            HttpRequest getReq = HttpRequest.newBuilder()
                    .uri(URI.create(chromaUrl + "/api/v1/collections/" + collectionName))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> getResp = httpClient.send(getReq, HttpResponse.BodyHandlers.ofString());
            if (getResp.statusCode() != 200) return List.of();

            @SuppressWarnings("unchecked")
            Map<String, Object> coll = objectMapper.readValue(getResp.body(), Map.class);
            String collectionId = String.valueOf(coll.get("id"));

            float[] embArr = embeddingModel.embed(question);
            List<Float> embedding = new ArrayList<>();
            for (float f : embArr) embedding.add(f);

            Map<String, Object> queryPayload = new HashMap<>();
            queryPayload.put("query_embeddings", List.of(embedding));
            queryPayload.put("n_results", topK);
            queryPayload.put("include", List.of("documents"));

            HttpRequest queryReq = HttpRequest.newBuilder()
                    .uri(URI.create(chromaUrl + "/api/v1/collections/" + collectionId + "/query"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(queryPayload)))
                    .build();
            HttpResponse<String> queryResp = httpClient.send(queryReq, HttpResponse.BodyHandlers.ofString());
            if (queryResp.statusCode() != 200) return List.of();

            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.readValue(queryResp.body(), Map.class);
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
                String line = rawLine == null ? "" : rawLine;
                String trimmed = line.trim();

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

    private int addFaqDocument(List<Document> documents,
                               int faqId,
                               String section,
                               String question,
                               StringBuilder answerBuilder) {
        if (question == null || question.isBlank()) {
            return faqId;
        }

        String answer = answerBuilder.toString().trim();
        if (answer.isBlank()) {
            return faqId;
        }

        int nextFaqId = faqId + 1;
        String text = "Section: " + section + "\n"
                + "Question: " + question + "\n"
                + "Answer: " + answer;
        Map<String, Object> metadata = Map.of(
                "faqId", nextFaqId,
                "section", section,
                "question", question,
                "answer", answer
        );
        documents.add(new Document(text, metadata));
        return nextFaqId;
    }

    private List<Document> retrieveRelevantDocuments(String question, int topK) {
        Map<Document, Integer> lexicalScores = faqEntries.stream()
            .collect(Collectors.toMap(doc -> doc, doc -> lexicalScore(question, doc)));

        List<Document> lexicalHits = faqEntries.stream()
            .filter(doc -> lexicalScores.getOrDefault(doc, 0) > 0)
            .sorted(Comparator.comparingInt((Document doc) -> lexicalScores.getOrDefault(doc, 0)).reversed())
            .limit(topK)
                .toList();
        List<Document> vectorHits = vectorStore.similaritySearch(
            SearchRequest.builder().query(expandQuery(question)).topK(topK).build());

        LinkedHashMap<String, Document> merged = new LinkedHashMap<>();
        for (Document document : lexicalHits) {
            merged.put(document.getText(), document);
        }
        for (Document document : vectorHits) {
            merged.putIfAbsent(document.getText(), document);
        }

        return merged.values().stream().limit(topK).toList();
    }

    private String expandQuery(String question) {
        String normalized = normalize(question);
        LinkedHashSet<String> parts = new LinkedHashSet<>();
        parts.add(question);

        if ((normalized.contains("product") || normalized.contains("products"))
                && (normalized.contains("refurb") || normalized.contains("new") || normalized.contains("used"))) {
            parts.add("products availability new products refurbished products certified refurbished");
        }

        if (normalized.contains("return") || normalized.contains("refund") || normalized.contains("replace")) {
            parts.add("return policy returns refunds replacements defective items unopened items");
        }
        if (normalized.contains("warranty") || normalized.contains("damage") || normalized.contains("protection")) {
            parts.add("warranty extended warranty accidental damage protection repair coverage");
        }
        if (normalized.contains("delivery") || normalized.contains("shipping")) {
            parts.add("shipping delivery tracking express same-day order status");
        }

        return String.join(" ", parts);
    }

    private int lexicalScore(String question, Document document) {
        String haystack = normalize(document.getText());
        Set<String> tokens = Arrays.stream(normalize(question).split("\\s+"))
                .filter(token -> !token.isBlank())
                .filter(token -> !STOP_WORDS.contains(token))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int score = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                score += 2;
            }
        }

        if (tokens.contains("return") && tokens.contains("policy") && haystack.contains("return policy")) {
            score += 5;
        }
        if ((tokens.contains("refund") || tokens.contains("replace") || tokens.contains("defective"))
                && haystack.contains("returns refunds")) {
            score += 3;
        }
        if (tokens.contains("warranty") && haystack.contains("warranty")) {
            score += 3;
        }

        return score;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}
