package com.mytechstore.hier.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.mytechstore.hier.dto.RagResponse;

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
public class StructuredPipelineService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);
    private final List<String> sectionHeaders = new ArrayList<>();

    public StructuredPipelineService(VectorStore vectorStore,
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

        sectionHeaders.clear();
        docs.forEach(d -> {
            String[] lines = d.getText().split("\\n");
            for (String line : lines) {
                if (line.startsWith("## ")) {
                    sectionHeaders.add(line.replace("## ", "").trim());
                }
            }
        });

        indexed.set(true);
        return "Indexed " + chunks.size() + " chunks and " + sectionHeaders.size() + " structured sections";
    }

    public RagResponse ask(String question) {
        if (!indexed.get()) {
            rebuildIndex();
        }

        String lower = question == null ? "" : question.toLowerCase();
        String selectedSection = sectionHeaders.stream()
                .filter(h -> lower.contains(firstWord(h).toLowerCase()))
                .findFirst()
                .orElse("General FAQ");

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder().query(question + " " + selectedSection).topK(4).build());
        String context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));

        String prompt = "You are hierarchical RAG assistant for MyTechStore. "
                + "Section selected: " + selectedSection + ". "
                + "Answer only from context.\n\n"
                + "Context:\n" + context + "\n\nQuestion: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        return new RagResponse(answer, selectedSection, hits.size());
    }

    private String firstWord(String text) {
        int idx = text.indexOf(' ');
        return idx > 0 ? text.substring(0, idx) : text;
    }
}