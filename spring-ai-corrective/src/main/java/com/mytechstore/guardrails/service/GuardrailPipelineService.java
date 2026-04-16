package com.mytechstore.guardrails.service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.mytechstore.guardrails.dto.RagResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;

@Service
public class GuardrailPipelineService {

    private static final Set<String> BLOCKED_TERMS = Set.of("password", "credit card", "hack", "exploit");

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);

    public GuardrailPipelineService(VectorStore vectorStore,
                                    ChatClient chatClient,
                                    @Value("${faq.source-file}") String sourceFile) {
        this.vectorStore = vectorStore;
        this.chatClient = chatClient;
        this.sourceFile = sourceFile;
    }

    public synchronized String rebuildIndex() {
        List<Document> docs = new TextReader(new FileSystemResource(sourceFile)).read();
        List<Document> chunks = new TokenTextSplitter().apply(docs);
        vectorStore.add(chunks);
        indexed.set(true);
        return "Indexed " + chunks.size() + " chunks with guardrail-ready pipeline";
    }

    public RagResponse ask(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        for (String term : BLOCKED_TERMS) {
            if (normalized.contains(term)) {
                return new RagResponse("Request blocked by policy guardrails.", true, "blocked-term:" + term, 0);
            }
        }

        if (!indexed.get()) {
            rebuildIndex();
        }

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(4).build());
        if (hits.isEmpty()) {
            return new RagResponse("I do not have enough FAQ context to answer this safely.", false, "low-retrieval-confidence", 0);
        }

        String context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        String prompt = "You are a guarded MyTechStore FAQ assistant. "
                + "Only answer from context and keep answer concise.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        return new RagResponse(answer, false, "ok", hits.size());
    }
}