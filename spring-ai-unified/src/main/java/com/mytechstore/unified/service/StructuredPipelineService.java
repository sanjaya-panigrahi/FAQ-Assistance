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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import jakarta.annotation.PreDestroy;

import com.mytechstore.unified.dto.HierarchicalRagResponse;
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
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int defaultTopK;
    private final AnalyticsReporter analyticsReporter;
    private final ChromaQueryService chromaQueryService;

    public StructuredPipelineService(VectorStore vectorStore,
                                     ChatClient chatClient,
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
    public HierarchicalRagResponse ask(String question, String customerId) {
        long t0 = System.currentTimeMillis();
        // Phase 1: Broad retrieval (10 chunks)
        List<String> chromaChunks = chromaQueryService.query(customerId, question, 10);
        String context;
        int chunksUsed;
        String strategy;
        if (!chromaChunks.isEmpty()) {
            // Phase 2: Group by first line, classify best section, rerank
            List<String> focused = twoPhaseFilter(question, chromaChunks);
            context = String.join("\n\n", focused);
            chunksUsed = focused.size();
            strategy = "hierarchical+2phase-chroma";
        } else {
            awaitIndexReady();
            List<Document> hits = retrieveRelevantDocuments(question, defaultTopK);
            context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = hits.size();
            strategy = "hierarchical+fallback-vectorstore";
        }

        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". "
            + "Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();
        long latencyMs = System.currentTimeMillis() - t0;

        HierarchicalRagResponse response = new HierarchicalRagResponse(answer, null, chunksUsed, strategy,
                "structured-retriever-2phase");
        analyticsReporter.postEvent(question, answer, customerId != null ? customerId : "default",
                "hierarchical", strategy, latencyMs, context);
        return response;
    }

    public Flux<ServerSentEvent<String>> askStream(String question, String customerId) {
        List<String> chromaChunks = chromaQueryService.query(customerId, question, 10);
        String context;
        int chunksUsed;
        String strategy;
        if (!chromaChunks.isEmpty()) {
            List<String> focused = twoPhaseFilter(question, chromaChunks);
            context = String.join("\n\n", focused);
            chunksUsed = focused.size();
            strategy = "hierarchical+2phase-chroma";
        } else {
            awaitIndexReady();
            List<Document> hits = retrieveRelevantDocuments(question, defaultTopK);
            context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = hits.size();
            strategy = "hierarchical+fallback-vectorstore";
        }
        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". "
            + "Answer the user's question using ONLY the provided FAQ context below. "
            + "Answer concisely and factually.\n\n"
            + "Context:\n" + context + "\n\nQuestion: " + question;

        String metaJson = "{\"chunksUsed\":" + chunksUsed + ",\"strategy\":\"" + strategy + "\",\"orchestrationStrategy\":\"structured-retriever-2phase\"}";
        ServerSentEvent<String> metaEvent = ServerSentEvent.<String>builder().event("meta").data(metaJson).build();
        ServerSentEvent<String> doneEvent = ServerSentEvent.<String>builder().event("done").data("").build();
        Flux<ServerSentEvent<String>> tokens = chatClient.prompt().user(prompt).stream().content()
            .map(chunk -> ServerSentEvent.<String>builder().event("token").data(chunk).build());
        return Flux.concat(Flux.just(metaEvent), tokens, Flux.just(doneEvent));
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

    /**
     * Two-phase hierarchical filter: groups chunks by first-line topic,
     * uses LLM to pick best section, then reranks within that section.
     */
    private List<String> twoPhaseFilter(String question, List<String> chunks) {
        if (chunks.size() <= 4) return chunks;

        // Group by first line as section proxy
        LinkedHashMap<String, List<String>> sections = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            String firstLine = chunks.get(i).split("\n")[0].trim();
            String key = firstLine.length() > 80 ? firstLine.substring(0, 80) : firstLine;
            if (key.isBlank()) key = "Section " + (i + 1);
            sections.computeIfAbsent(key, k -> new ArrayList<>()).add(chunks.get(i));
        }

        if (sections.size() <= 1) {
            // All in one section — just rerank
            return rerankChunks(question, chunks, 4);
        }

        // LLM picks best section
        List<String> sectionKeys = new ArrayList<>(sections.keySet());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sectionKeys.size(); i++) {
            sb.append(i + 1).append(". ").append(sectionKeys.get(i))
              .append(" (").append(sections.get(sectionKeys.get(i)).size()).append(" docs)\n");
        }

        String selectedKey;
        try {
            String result = chatClient.prompt().user(
                "Which section is most relevant to the question? Respond with ONLY the number.\n\nQuestion: "
                + question + "\n\nSections:\n" + sb).call().content().trim();
            int idx = Math.max(0, Math.min(Integer.parseInt(result.split("\\s+")[0]) - 1, sectionKeys.size() - 1));
            selectedKey = sectionKeys.get(idx);
        } catch (Exception e) {
            selectedKey = sectionKeys.get(0);
        }

        // Combine selected section + top 2 from others
        List<String> candidates = new ArrayList<>(sections.get(selectedKey));
        for (String key : sectionKeys) {
            if (!key.equals(selectedKey)) {
                List<String> others = sections.get(key);
                candidates.addAll(others.subList(0, Math.min(2, others.size())));
            }
        }

        return rerankChunks(question, candidates, 4);
    }

    private List<String> rerankChunks(String question, List<String> chunks, int topK) {
        if (chunks.size() <= topK) return chunks;
        record ScoredChunk(String text, double score) {}
        List<ScoredChunk> scored = new ArrayList<>();
        for (String chunk : chunks) {
            String truncated = chunk.length() > 500 ? chunk.substring(0, 500) : chunk;
            String prompt = "Rate the relevance of this document to the question on a scale of 0-10. "
                + "Respond with ONLY a number.\n\nQuestion: " + question + "\n\nDocument:\n" + truncated;
            try {
                String result = chatClient.prompt().user(prompt).call().content().trim();
                double score = Double.parseDouble(result.split("\\s+")[0]);
                score = Math.max(0, Math.min(10, score));
                scored.add(new ScoredChunk(chunk, score));
            } catch (Exception e) {
                scored.add(new ScoredChunk(chunk, 5.0));
            }
        }
        scored.sort((a, b) -> Double.compare(b.score(), a.score()));
        return scored.stream().limit(topK).map(ScoredChunk::text).toList();
    }
}