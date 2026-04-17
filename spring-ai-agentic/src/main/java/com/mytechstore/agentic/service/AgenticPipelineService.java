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

import com.mytechstore.agentic.dto.RagResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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

    public AgenticPipelineService(VectorStore vectorStore, ChatClient chatClient,
                                  @Value("${faq.source-file}") String sourceFile) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
    }

    public synchronized String rebuildIndex() {
        List<Document> docs = parseFaqDocuments();
        vectorStore.add(docs);
        faqEntries = docs;
        indexed.set(true);
        return "Indexed " + docs.size() + " FAQ entries from " + sourceFile;
    }

    public RagResponse ask(String question) {
        if (!indexed.get()) {
            rebuildIndex();
        }

        // Agent-style orchestration: planner step chooses how broad retrieval should be.
        int topK = question.toLowerCase().contains("compare") ? 6 : 4;
        List<Document> retrieved = retrieveRelevantDocuments(question, topK);

        String context = retrieved.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        String prompt = "You are MyTechStore FAQ assistant. Use only the context. "
            + "If the question is about a specific product type such as laptops, and the context provides a general store policy"
            + " with no product-specific exception, answer with the general policy and say it applies unless a product page or"
            + " brand policy states otherwise. If context is missing, say you do not have enough policy data.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;

        String answer = chatClient.prompt().user(prompt).call().content();
        return new RagResponse(answer, "plan->retrieve->respond", retrieved.size());
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
