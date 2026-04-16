package com.mytechstore.agentic.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.mytechstore.agentic.dto.RagResponse;

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
public class AgenticPipelineService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);

    public AgenticPipelineService(VectorStore vectorStore, ChatClient chatClient,
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
        return "Indexed " + chunks.size() + " chunks from " + sourceFile;
    }

    public RagResponse ask(String question) {
        if (!indexed.get()) {
            rebuildIndex();
        }

        // Agent-style orchestration: planner step chooses how broad retrieval should be.
        int topK = question.toLowerCase().contains("compare") ? 6 : 4;
        List<Document> retrieved = vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(topK).build());

        String context = retrieved.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        String prompt = "You are MyTechStore FAQ assistant. Use only the context. "
                + "If context is missing, say you do not have enough policy data.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;

        String answer = chatClient.prompt().user(prompt).call().content();
        return new RagResponse(answer, "plan->retrieve->respond", retrieved.size());
    }
}
