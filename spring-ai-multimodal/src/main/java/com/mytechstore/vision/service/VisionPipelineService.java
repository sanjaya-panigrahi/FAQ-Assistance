package com.mytechstore.vision.service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.mytechstore.vision.dto.VisionRagResponse;

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
public class VisionPipelineService {

    private final VectorStore vectorStore;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);

    public VisionPipelineService(VectorStore vectorStore,
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
        return "Indexed " + chunks.size() + " chunks for multimodal retrieval";
    }

    public VisionRagResponse ask(String question, String imageDescription) {
        if (!indexed.get()) {
            rebuildIndex();
        }

        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder().query(question).topK(4).build());
        String context = hits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));
        String prompt = "You are MyTechStore multimodal FAQ assistant. "
                + "Use FAQ context plus image metadata to answer.\n\n"
                + "Image metadata: " + (imageDescription == null ? "not provided" : imageDescription) + "\n\n"
                + "FAQ context:\n" + context + "\n\n"
                + "Question: " + question;

        String answer = chatClient.prompt().user(prompt).call().content();
        return new VisionRagResponse(answer, hits.size());
    }
}