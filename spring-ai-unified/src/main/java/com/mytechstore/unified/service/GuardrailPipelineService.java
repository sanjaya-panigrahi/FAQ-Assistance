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

import com.mytechstore.unified.dto.CorrectiveRagResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;

@Service
public class GuardrailPipelineService {

    private static final Logger log = LoggerFactory.getLogger(GuardrailPipelineService.class);

    private static final Set<String> STOP_WORDS = Set.of(
            "a", "an", "and", "are", "at", "be", "by", "can", "do", "for", "from", "how", "i", "if",
            "in", "is", "it", "me", "my", "of", "on", "or", "the", "to", "we", "what", "when", "where",
            "which", "with", "you", "your"
    );

    private static final String GRADING_PROMPT =
            "You are a relevance grader. Given a user question and a retrieved FAQ document, " +
            "determine if the document contains information relevant to answering the question.\n" +
            "Respond with ONLY one word: RELEVANT or IRRELEVANT.\n\n" +
            "Question: %s\n\nDocument:\n%s";

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
    private final String tavilyApiKey;
    private final String tavilyUrl;
    private final WebClient webClient;

    public GuardrailPipelineService(VectorStore vectorStore,
                                    ChatClient chatClient,
                                    @Value("${faq.source-file}") String sourceFile,
                                    EmbeddingModel embeddingModel,
                                    @Value("${retrieval.top-k:6}") int defaultTopK,
                                    AnalyticsReporter analyticsReporter,
                                    ChromaQueryService chromaQueryService,
                                    @Value("${tavily.api-key:}") String tavilyApiKey,
                                    @Value("${tavily.url:https://api.tavily.com/search}") String tavilyUrl,
                                    WebClient webClient) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
        this.embeddingModel = embeddingModel;
        this.defaultTopK = defaultTopK;
        this.analyticsReporter = analyticsReporter;
        this.chromaQueryService = chromaQueryService;
        this.tavilyApiKey = tavilyApiKey;
        this.tavilyUrl = tavilyUrl;
        this.webClient = webClient;
    }

    @CacheEvict(value = "guardrailAnswers", allEntries = true)
    public synchronized String rebuildIndex() {
        List<Document> docs = parseFaqDocuments();
        vectorStore.add(docs);
        faqEntries = docs;
        indexed.set(true);
        return "Indexed " + docs.size() + " FAQ entries with guardrail-ready pipeline";
    }

    @Cacheable(value = "guardrailAnswers", key = "(#customerId == null ? 'default' : #customerId) + ':' + #question")
    public CorrectiveRagResponse ask(String question, String customerId) {
        long t0 = System.currentTimeMillis();
        List<String> chromaChunks = chromaQueryService.query(customerId, question, defaultTopK);
        String context;
        int chunksUsed;
        if (!chromaChunks.isEmpty()) {
            context = String.join("\n\n", chromaChunks);
            chunksUsed = chromaChunks.size();
        } else {
            if (!indexed.get()) {
                rebuildIndex();
            }
            List<Document> hits = retrieveRelevantDocuments(question, defaultTopK);
            if (hits.isEmpty()) {
                return new CorrectiveRagResponse("I do not have enough FAQ context to answer this safely.", false,
                        "low-retrieval-confidence", 0, "crag+no-retrieval",
                        "springai-crag");
            }
            context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = hits.size();
        }

        if (context.isBlank()) {
            return new CorrectiveRagResponse("I do not have enough FAQ context to answer this safely.", false,
                    "low-retrieval-confidence", 0, "crag+no-retrieval",
                    "springai-crag");
        }

        // --- CRAG: LLM-based relevance grading ---
        String[] chunks = context.split("\n\n");
        List<String> relevantChunks = new ArrayList<>();
        int totalChunks = chunks.length;
        for (String chunk : chunks) {
            if (chunk.isBlank()) continue;
            String gradingPrompt = String.format(GRADING_PROMPT, question, chunk);
            try {
                String grade = chatClient.prompt().user(gradingPrompt).call().content().trim().toUpperCase();
                if (grade.contains("RELEVANT") && !grade.contains("IRRELEVANT")) {
                    relevantChunks.add(chunk);
                }
            } catch (Exception e) {
                relevantChunks.add(chunk); // on error, keep the chunk
            }
        }

        double relevanceRatio = totalChunks > 0 ? (double) relevantChunks.size() / totalChunks : 0.0;

        // --- CRAG: Web search fallback for low/ambiguous relevance ---
        String webContext = "";
        String strategy;
        if (relevanceRatio < 0.5) {
            webContext = webSearchFallback(question);
            strategy = !webContext.isEmpty() ? "crag+web-search-fallback" : "crag+low-relevance";
        } else if (relevanceRatio < 1.0) {
            webContext = webSearchFallback(question);
            strategy = !webContext.isEmpty() ? "crag+ambiguous-supplemented" : "crag+ambiguous-local";
        } else {
            strategy = "crag+all-relevant";
        }

        // Build final context
        StringBuilder finalContext = new StringBuilder(String.join("\n\n", relevantChunks));
        if (!webContext.isEmpty()) {
            finalContext.append("\n\n--- Web Search Results ---\n").append(webContext);
        }

        if (finalContext.toString().isBlank()) {
            return new CorrectiveRagResponse("I do not have enough FAQ context to answer this safely.", false,
                    "low-retrieval-confidence", 0, "crag+empty-after-grading",
                    "springai-crag");
        }

        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". "
            + "Answer the user's question using ONLY the provided context below. "
            + "If web search results are included, prefer local FAQ context but use web results to supplement. "
            + "Answer concisely and factually.\n\n"
                + "Context:\n" + finalContext + "\n\nQuestion: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        long latencyMs = System.currentTimeMillis() - t0;
        CorrectiveRagResponse response = new CorrectiveRagResponse(answer, false, "ok", relevantChunks.size(), strategy,
                "springai-crag");
        analyticsReporter.postEvent(question, answer, customerId != null ? customerId : "default",
                "corrective", strategy, latencyMs, finalContext.toString());
        return response;
    }

    private String webSearchFallback(String question) {
        if (tavilyApiKey == null || tavilyApiKey.isBlank()) {
            log.debug("TAVILY_API_KEY not set — skipping web search fallback");
            return "";
        }
        try {
            Map<String, Object> body = Map.of(
                "api_key", tavilyApiKey,
                "query", question,
                "max_results", 3,
                "search_depth", "basic",
                "include_answer", true
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> result = webClient.post()
                .uri(tavilyUrl)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .block(java.time.Duration.ofSeconds(10));
            if (result == null) return "";

            StringBuilder sb = new StringBuilder();
            Object answerObj = result.get("answer");
            if (answerObj != null && !answerObj.toString().isBlank()) {
                sb.append("Web Summary: ").append(answerObj).append("\n\n");
            }
            Object resultsObj = result.get("results");
            if (resultsObj instanceof List<?> resultsList) {
                for (int i = 0; i < Math.min(3, resultsList.size()); i++) {
                    if (resultsList.get(i) instanceof Map<?, ?> r) {
                        sb.append("Source: ").append(r.getOrDefault("title", "")).append("\n")
                          .append(r.getOrDefault("content", "")).append("\n\n");
                    }
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("Tavily web search failed: {}", e.getMessage());
            return "";
        }
    }

    public Flux<ServerSentEvent<String>> askStream(String question, String customerId) {
        List<String> chromaChunks = chromaQueryService.query(customerId, question, defaultTopK);
        String context;
        int chunksUsed;
        if (!chromaChunks.isEmpty()) {
            context = String.join("\n\n", chromaChunks);
            chunksUsed = chromaChunks.size();
        } else {
            if (!indexed.get()) { rebuildIndex(); }
            List<Document> hits = retrieveRelevantDocuments(question, defaultTopK);
            if (hits.isEmpty()) {
                return Flux.just(
                    ServerSentEvent.<String>builder().event("meta").data("{\"chunksUsed\":0,\"strategy\":\"crag+no-retrieval\",\"orchestrationStrategy\":\"springai-crag\"}").build(),
                    ServerSentEvent.<String>builder().event("token").data("I do not have enough FAQ context to answer this safely.").build(),
                    ServerSentEvent.<String>builder().event("done").data("").build()
                );
            }
            context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
            chunksUsed = hits.size();
        }
        if (context.isBlank()) {
            return Flux.just(
                ServerSentEvent.<String>builder().event("meta").data("{\"chunksUsed\":0,\"strategy\":\"crag+no-retrieval\",\"orchestrationStrategy\":\"springai-crag\"}").build(),
                ServerSentEvent.<String>builder().event("token").data("I do not have enough FAQ context to answer this safely.").build(),
                ServerSentEvent.<String>builder().event("done").data("").build()
            );
        }

        // CRAG grading for streaming
        String[] chunks = context.split("\n\n");
        List<String> relevantChunks = new ArrayList<>();
        for (String chunk : chunks) {
            if (chunk.isBlank()) continue;
            String gradingPrompt = String.format(GRADING_PROMPT, question, chunk);
            try {
                String grade = chatClient.prompt().user(gradingPrompt).call().content().trim().toUpperCase();
                if (grade.contains("RELEVANT") && !grade.contains("IRRELEVANT")) {
                    relevantChunks.add(chunk);
                }
            } catch (Exception e) {
                relevantChunks.add(chunk);
            }
        }

        double relevanceRatio = chunks.length > 0 ? (double) relevantChunks.size() / chunks.length : 0.0;
        String webContext = relevanceRatio < 1.0 ? webSearchFallback(question) : "";
        String strategy = relevanceRatio >= 1.0 ? "crag+all-relevant"
            : (!webContext.isEmpty() ? "crag+web-supplemented" : "crag+local-only");

        StringBuilder finalContext = new StringBuilder(String.join("\n\n", relevantChunks));
        if (!webContext.isEmpty()) {
            finalContext.append("\n\n--- Web Search Results ---\n").append(webContext);
        }

        String customerLabel = (customerId != null && !customerId.isBlank()) ? customerId.trim() : "the company";
        String prompt = "You are a FAQ assistant for " + customerLabel + ". "
            + "Answer the user's question using ONLY the provided context below. "
            + "If web search results are included, prefer local FAQ context but use web results to supplement. "
            + "Answer concisely and factually.\n\n"
            + "Context:\n" + finalContext + "\n\nQuestion: " + question;

        String metaJson = "{\"chunksUsed\":" + relevantChunks.size() + ",\"strategy\":\"" + strategy + "\",\"orchestrationStrategy\":\"springai-crag\"}";
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