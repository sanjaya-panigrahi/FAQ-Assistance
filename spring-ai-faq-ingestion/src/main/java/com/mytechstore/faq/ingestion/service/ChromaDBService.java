package com.mytechstore.faq.ingestion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ChromaDB Integration Service
 *
 * Handles all vector store operations:
 * - Collection management (create, delete, get)
 * - Document embedding and indexing
 * - Similarity search
 * - Metadata management
 *
 * Architecture Decision: Direct HTTP client to ChromaDB
 * - ChromaDB provides REST API for easy integration
 * - No heavyweight ORM needed for vector operations
 * - Lightweight, fast, embeddable
 * - Persistent storage via volume mount
 */
@Slf4j
@Service
public class ChromaDBService {

    private final String chromaUrl;
    private final String persistDirectory;
    private final String collectionNamePrefix;
    private final EmbeddingModel embeddingModel;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // ChromaDB v2 tenant/database scope (defaults match server defaults)
    private static final String CHROMA_TENANT   = "default_tenant";
    private static final String CHROMA_DATABASE  = "default_database";

    public ChromaDBService(
        EmbeddingModel embeddingModel,
        @Value("${app.chroma.url:http://localhost:8000}") String chromaUrl,
        @Value("${app.chroma.persist-directory}") String persistDirectory,
        @Value("${app.chroma.collection-name-prefix}") String collectionNamePrefix
    ) {
        this.embeddingModel = embeddingModel;
        this.chromaUrl = chromaUrl;
        this.persistDirectory = persistDirectory;
        this.collectionNamePrefix = collectionNamePrefix;
        this.httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.objectMapper = new ObjectMapper();
    }

    /** Base path for all v2 collection operations. */
    private String collectionsBase() {
        return chromaUrl + "/api/v2/tenants/" + CHROMA_TENANT
             + "/databases/" + CHROMA_DATABASE + "/collections";
    }

    /**
     * Check if ChromaDB is running and accessible
     * @return true if service is accessible
     */
    public boolean isHealthy() {
        // Try v2 first (current Chroma API), then fallback to v1 for older images.
        if (checkHeartbeat("/api/v2/heartbeat")) {
            return true;
        }
        if (checkHeartbeat("/api/v1/heartbeat")) {
            return true;
        }

        log.warn("ChromaDB health check failed for both v2 and v1 endpoints");
        return false;
    }

    private boolean checkHeartbeat(String path) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(chromaUrl + path))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.debug("ChromaDB heartbeat check failed for {}: {}", path, e.getMessage());
            return false;
        }
    }

    /**
     * Create a new collection for a customer
     * @param customerId Customer identifier
     * @param collectionName Custom collection name
     * @return Collection metadata
     */
    public Map<String, Object> createCollection(String customerId, String collectionName) {
        try {
            String finalName = collectionName != null ? collectionName : collectionNamePrefix + customerId;

            Map<String, Object> payload = new HashMap<>();
            payload.put("name", finalName);
            payload.put("metadata", Map.of(
                "customer_id", customerId,
                "created_at", System.currentTimeMillis()
            ));

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase()))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.info("Created ChromaDB collection: {}", finalName);
                return objectMapper.readValue(response.body(), Map.class);
            } else {
                // Some Chroma versions return non-2xx for existing collections.
                // Fallback to lookup-by-name so indexing can continue idempotently.
                log.warn(
                    "Create collection returned non-success for {} (Status: {}, Body: {})",
                    finalName,
                    response.statusCode(),
                    response.body()
                );

                Map<String, Object> existing = getCollection(finalName);
                if (existing != null) {
                    log.info("Using existing ChromaDB collection: {}", finalName);
                    return existing;
                }

                log.error("Failed to create or resolve collection: {}", finalName);
                return null;
            }
        } catch (Exception e) {
            log.error("Error creating ChromaDB collection", e);
            return null;
        }
    }

    /**
     * Get collection by name
     * @param collectionName Name of collection
     * @return Collection metadata or null
     */
    public Map<String, Object> getCollection(String collectionName) {
        try {
            String encodedCollectionName = URLEncoder.encode(collectionName, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase() + "/" + encodedCollectionName))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), Map.class);
            }
            return null;
        } catch (Exception e) {
            log.warn("Collection not found or error retrieving: {}", collectionName);
            return null;
        }
    }

    /**
     * Add documents to collection with embeddings
     * @param collectionName Target collection
     * @param documents Document texts to embed and store
     * @param metadatas Optional metadata for each document
     * @param ids Document IDs
     * @return Success status
     */
    public boolean addDocuments(String collectionName, List<String> documents,
                               List<Map<String, Object>> metadatas, List<String> ids) {
        try {
            String collectionId = resolveCollectionId(collectionName);
            if (collectionId == null) {
                log.error("Unable to resolve collection id for: {}", collectionName);
                return false;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("documents", documents);
            payload.put("metadatas", metadatas != null ? metadatas : new ArrayList<>());
            payload.put("ids", ids);

            // Chroma 0.5.x expects query_embeddings at search time; store embeddings explicitly at index time.
            List<List<Double>> embeddings = new ArrayList<>();
            for (String document : documents) {
                float[] vector = embeddingModel.embed(document);
                if (vector == null || vector.length == 0) {
                    log.error("Failed to embed one of the input documents for collection {}", collectionName);
                    return false;
                }
                List<Double> values = new ArrayList<>(vector.length);
                for (float v : vector) {
                    values.add((double) v);
                }
                embeddings.add(values);
            }
            payload.put("embeddings", embeddings);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase() + "/" + collectionId + "/add"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                log.debug("Added {} documents to collection: {}", documents.size(), collectionName);
                return true;
            } else {
                log.error(
                    "Failed to add documents: {} (Status: {}, Body: {})",
                    collectionName,
                    response.statusCode(),
                    response.body()
                );
                return false;
            }
        } catch (Exception e) {
            log.error("Error adding documents to ChromaDB", e);
            return false;
        }
    }

    /**
     * Query collection with similarity search
     * @param collectionName Target collection
     * @param queryTexts Query texts
     * @param topK Number of results per query
     * @param whereFilter Optional metadata filter
     * @return Query results with scores
     */
    public Map<String, Object> query(String collectionName, List<String> queryTexts,
                                     int topK, Map<String, Object> whereFilter) {
        try {
            String collectionId = resolveCollectionId(collectionName);
            if (collectionId == null) {
                log.error("Unable to resolve collection id for query: {}", collectionName);
                return new HashMap<>();
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("query_texts", queryTexts);
            payload.put("n_results", topK);
            if (whereFilter != null) {
                payload.put("where", whereFilter);
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase() + "/" + collectionId + "/query"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return objectMapper.readValue(response.body(), Map.class);
            }
            return new HashMap<>();
        } catch (Exception e) {
            log.error("Error querying ChromaDB", e);
            return new HashMap<>();
        }
    }

    /**
     * Delete a document from collection
     * @param collectionName Target collection
     * @param documentIds IDs to delete
     * @return Success status
     */
    public boolean deleteDocuments(String collectionName, List<String> documentIds) {
        try {
            String collectionId = resolveCollectionId(collectionName);
            if (collectionId == null) {
                log.error("Unable to resolve collection id for delete: {}", collectionName);
                return false;
            }

            Map<String, Object> payload = new HashMap<>();
            payload.put("ids", documentIds);

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase() + "/" + collectionId + "/delete"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("Error deleting documents from ChromaDB", e);
            return false;
        }
    }

    /**
     * Delete entire collection
     * @param collectionName Collection to delete
     * @return Success status
     */
    public boolean deleteCollection(String collectionName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase() + "/" + collectionName))
                .DELETE()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 || response.statusCode() == 204;
        } catch (Exception e) {
            log.error("Error deleting collection: {}", collectionName, e);
            return false;
        }
    }

    /**
     * Get collection count
     * @param collectionName Collection name
     * @return Document count in collection
     */
    public int getCollectionCount(String collectionName) {
        try {
            String collectionId = resolveCollectionId(collectionName);
            if (collectionId == null) {
                return 0;
            }

            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(collectionsBase() + "/" + collectionId + "/count"))
                .GET()
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                Map<String, Object> result = objectMapper.readValue(response.body(), Map.class);
                Object count = result.get("count");
                return count instanceof Number ? ((Number) count).intValue() : 0;
            }
            return 0;
        } catch (Exception e) {
            log.warn("Error getting collection count", e);
            return 0;
        }
    }

    private String resolveCollectionId(String collectionName) {
        Map<String, Object> collection = getCollection(collectionName);
        if (collection == null) {
            return null;
        }

        Object id = collection.get("id");
        return id != null ? String.valueOf(id) : null;
    }

}
