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

import com.mytechstore.graphrag.dto.RagResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

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

    public GraphPipelineService(VectorStore vectorStore,
                                Neo4jClient neo4jClient,
                                ChatClient chatClient,
                                @Value("${faq.source-file}") String sourceFile) {
        this.vectorStore = vectorStore;
        this.neo4jClient = neo4jClient;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
    }

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

    public RagResponse ask(String question) {
        if (!indexed.get()) {
            rebuildIndex();
        }

        List<Document> vectorHits = vectorStore.similaritySearch(
                                SearchRequest.builder().query(expandQuery(question)).topK(4).build());
        String vectorContext = vectorHits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));

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
        String prompt = "You are Graph RAG FAQ assistant for MyTechStore.\n"
                                + "Use vector context and graph facts to answer. If the context provides a general policy and no product-specific"
                                + " exception, answer with the general policy.\n\n"
                + "Vector context:\n" + vectorContext + "\n\n"
                + "Graph facts:\n" + graphContext + "\n\n"
                + "Question: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        return new RagResponse(answer, vectorHits.size(), graphFacts.size());
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