package com.mytechstore.unified.service;

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
import java.time.Duration;
import java.util.HashMap;

import com.mytechstore.unified.dto.AgenticRagResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int defaultTopK;
    private final AnalyticsReporter analyticsReporter;
    private final ChromaQueryService chromaQueryService;

    public AgenticPipelineService(VectorStore vectorStore, ChatClient chatClient,
                                  @Value("${faq.source-file}") String sourceFile,
                                  EmbeddingModel embeddingModel,
                                  @Value("${retrieval.top-k:6}") int defaultTopK,
                                  AnalyticsReporter analyticsReporter,
                                  ChromaQueryService chromaQueryService) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
        this.embeddingModel = embeddingModel;
        this.defaultTopK = defaultTopK;
        this.analyticsReporter = analyticsReporter;
        this.chromaQueryService = chromaQueryService;
    }

    @CacheEvict(value = {"agenticAnswers", "agenticChroma"}, allEntries = true)
    public synchronized String rebuildIndex() {
        List<Document> docs = parseFaqDocuments();
        vectorStore.add(docs);
        faqEntries = docs;
        indexed.set(true);
        return "Indexed " + docs.size() + " FAQ entries from " + sourceFile;
    }

    @Cacheable(value = "agenticAnswers", key = "#customerId + ':' + #question")
    public AgenticRagResponse ask(String question, String customerId) {
        // Agent-style orchestration: planner step chooses how broad retrieval should be.
        int topK = defaultTopK;
        String retrievalQuery = question;

        List<String> chromaChunks = chromaQueryService.query(customerId, retrievalQuery, topK);
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

        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;

        String answer = chatClient.prompt().user(prompt).call().content();
        AgenticRagResponse response = new AgenticRagResponse(answer, "chroma-direct+agent-plan", chunksUsed, "springai-agent-orchestration");
        analyticsReporter.postEvent(question, answer, customerId != null ? customerId : "default",
                "agentic", "chroma-direct+agent-plan", 0, context);
        return response;
    }

    public Flux<ServerSentEvent<String>> askStream(String question, String customerId) {
        int topK = defaultTopK;
        List<String> chromaChunks = chromaQueryService.query(customerId, question, topK);
        String context;
        int chunksUsed;
        if (!chromaChunks.isEmpty()) {
            context = String.join("\n\n", chromaChunks);
            chunksUsed = chromaChunks.size();
        } else {
            if (!indexed.get()) { rebuildIndex(); }
            List<Document> retrieved = retrieveRelevantDocuments(question, topK);
            context = retrieved.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = retrieved.size();
        }
        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
            + "Context:\n" + context + "\n\nQuestion: " + question;

        String metaJson = "{\"chunksUsed\":" + chunksUsed + ",\"strategy\":\"chroma-direct+agent-plan\",\"orchestrationStrategy\":\"springai-agent-orchestration\"}";
        ServerSentEvent<String> metaEvent = ServerSentEvent.<String>builder().event("meta").data(metaJson).build();
        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder().event("done").data("").build();

        Flux<ServerSentEvent<String>> tokens = chatClient.prompt().user(prompt).stream().content()
            .map(chunk -> ServerSentEvent.<String>builder().event("token").data(chunk).build());

        return Flux.concat(Flux.just(metaEvent), tokens, Flux.just(doneEvent));
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
            SearchRequest.builder().query(question).topK(topK).build());

        LinkedHashMap<String, Document> merged = new LinkedHashMap<>();
        for (Document document : lexicalHits) {
            merged.put(document.getText(), document);
        }
        for (Document document : vectorHits) {
            merged.putIfAbsent(document.getText(), document);
        }

        return merged.values().stream().limit(topK).toList();
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
