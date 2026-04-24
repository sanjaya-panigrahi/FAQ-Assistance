package com.mytechstore.hier.service;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.time.Duration;
import java.util.HashMap;

import jakarta.annotation.PreDestroy;

import com.mytechstore.hier.dto.RagResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class StructuredPipelineService {

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "at", "be", "by", "can", "do", "for", "from", "how", "i", "if",
            "in", "is", "it", "me", "my", "of", "on", "or", "the", "to", "we", "what", "when", "where",
            "which", "with", "you", "your"
    );

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);
    private final AtomicBoolean indexing = new AtomicBoolean(false);
    private final List<String> sectionHeaders = new ArrayList<>();
    private final ExecutorService rebuildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "structured-pipeline-rebuild");
        thread.setDaemon(true);
        return thread;
    });
    private volatile List<Document> faqEntries = List.of();
    private volatile CompletableFuture<String> rebuildTask;
    private volatile String indexedCorpusSignature = "";
    private final EmbeddingModel embeddingModel;
    private final String chromaUrl;
    private final String collectionPrefix;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WebClient webClient;
    private final int defaultTopK;

    public StructuredPipelineService(VectorStore vectorStore,
                                     ChatClient chatClient,
                                     @Value("${faq.source-file}") String sourceFile,
                                     EmbeddingModel embeddingModel,
                                     @Value("${chroma.url:http://chroma-faq:8000}") String chromaUrl,
                                     @Value("${chroma.collection-prefix:faq_}") String collectionPrefix,
                                     @Value("${retrieval.top-k:6}") int defaultTopK,
                                     WebClient webClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
        this.embeddingModel = embeddingModel;
        this.chromaUrl = chromaUrl;
        this.collectionPrefix = collectionPrefix;
        this.defaultTopK = defaultTopK;
        this.webClient = webClient;
    }

    @CacheEvict(value = "hierarchicalAnswers", allEntries = true)
    public synchronized String rebuildIndex() {
        List<Document> docs = parseFaqDocuments();
        String corpusSignature = corpusSignature(docs);

        if (indexed.get() && corpusSignature.equals(indexedCorpusSignature) && !indexing.get()) {
            refreshStructuredState(docs);
            return "Index already up to date for " + docs.size() + " FAQ entries";
        }
        if (indexing.get()) {
            return "Index rebuild already in progress";
        }

        scheduleRebuild(docs, corpusSignature);
        return indexed.get() ? "Index rebuild started for " + docs.size() + " FAQ entries"
                : "Initial index build started for " + docs.size() + " FAQ entries";
    }

    @Cacheable(value = "hierarchicalAnswers", key = "(#customerId == null ? 'default' : #customerId) + ':' + #question")
    public RagResponse ask(String question, String customerId) {
        List<String> chromaChunks = queryChroma(customerId, question, defaultTopK);
        String context;
        int chunksUsed;
        if (!chromaChunks.isEmpty()) {
            context = String.join("\n\n", chromaChunks);
            chunksUsed = chromaChunks.size();
        } else {
            awaitIndexReady();
            List<Document> hits = retrieveRelevantDocuments(question, defaultTopK);
            context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = hits.size();
        }

        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". "
            + "Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        return new RagResponse(answer, null, chunksUsed, "chroma-direct+hierarchical-section",
                "structured-retriever-layer");
    }

    private List<String> queryChroma(String customerId, String question, int topK) {
        if (customerId == null || customerId.isBlank()) return List.of();
        try {
            String collectionName = collectionPrefix + customerId.trim();
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

    @PreDestroy
    void shutdownExecutor() {
        rebuildExecutor.shutdownNow();
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

    private void awaitIndexReady() {
        if (indexed.get()) {
            return;
        }

        CompletableFuture<String> taskToWaitFor;
        synchronized (this) {
            if (indexed.get()) {
                return;
            }
            if (!indexing.get()) {
                List<Document> docs = parseFaqDocuments();
                scheduleRebuild(docs, corpusSignature(docs));
            }
            taskToWaitFor = rebuildTask;
        }

        if (taskToWaitFor == null) {
            throw new IllegalStateException("Index rebuild task was not created");
        }

        try {
            taskToWaitFor.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for index rebuild", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("Failed to rebuild FAQ index", ex.getCause());
        }
    }

    private void scheduleRebuild(List<Document> docs, String corpusSignature) {
        indexing.set(true);
        rebuildTask = CompletableFuture.supplyAsync(() -> doRebuildIndex(docs, corpusSignature), rebuildExecutor)
                .whenComplete((result, error) -> indexing.set(false));
    }

    private String doRebuildIndex(List<Document> docs, String corpusSignature) {
        vectorStore.add(docs);
        synchronized (this) {
            refreshStructuredState(docs);
            indexedCorpusSignature = corpusSignature;
            indexed.set(true);
        }
        return "Indexed " + docs.size() + " FAQ entries and " + sectionHeaders.size() + " structured sections";
    }

    private void refreshStructuredState(List<Document> docs) {
        faqEntries = docs;
        sectionHeaders.clear();
        docs.stream()
                .map(document -> document.getMetadata().get("section"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .distinct()
                .forEach(sectionHeaders::add);
    }

    private String corpusSignature(List<Document> docs) {
        return docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n---\n"));
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
        if (tokens.contains("warranty") && haystack.contains("warranty")) {
            score += 3;
        }
        return score;
    }

    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9 ]", " ").replaceAll("\\s+", " ").trim();
    }
}