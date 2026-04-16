package com.mytechstore.graphrag.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import com.mytechstore.graphrag.dto.RagResponse;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Service;

@Service
public class GraphPipelineService {

    private final VectorStore vectorStore;
    private final Neo4jClient neo4jClient;
    private final ChatClient chatClient;
    private final String sourceFile;
    private final AtomicBoolean indexed = new AtomicBoolean(false);

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
        List<Document> docs = new TextReader(new FileSystemResource(sourceFile)).read();
        List<Document> chunks = new TokenTextSplitter().apply(docs);
        vectorStore.add(chunks);

        neo4jClient.query("MATCH (n:FaqChunk) DETACH DELETE n").run();
        List<Map<String, String>> rows = chunks.stream()
                .map(d -> Map.of("id", UUID.randomUUID().toString(), "text", d.getText()))
                .toList();
        neo4jClient.query("UNWIND $rows AS row CREATE (:FaqChunk {id: row.id, text: row.text})")
                .bind(rows).to("rows").run();

        indexed.set(true);
        return "Indexed " + chunks.size() + " chunks into vector and graph layers";
    }

    public RagResponse ask(String question) {
        if (!indexed.get()) {
            rebuildIndex();
        }

        List<Document> vectorHits = vectorStore.similaritySearch(
                SearchRequest.builder().query(question).topK(4).build());
        String vectorContext = vectorHits.stream().map(Document::getText).collect(Collectors.joining("\n\n"));

        List<String> graphFacts = neo4jClient.query(
                        "MATCH (f:FaqChunk) WHERE toLower(f.text) CONTAINS toLower($q) RETURN f.text AS text LIMIT 3")
                .bind(question).to("q")
                .fetch().all().stream()
                .map(row -> row.get("text").toString())
                .toList();

        String graphContext = String.join("\n", graphFacts);
        String prompt = "You are Graph RAG FAQ assistant for MyTechStore.\n"
                + "Use vector context and graph facts to answer.\n\n"
                + "Vector context:\n" + vectorContext + "\n\n"
                + "Graph facts:\n" + graphContext + "\n\n"
                + "Question: " + question;
        String answer = chatClient.prompt().user(prompt).call().content();

        return new RagResponse(answer, vectorHits.size(), graphFacts.size());
    }
}