package com.mytechstore.faq.ingestion.service;

import com.mytechstore.faq.ingestion.dto.*;
import com.mytechstore.faq.ingestion.model.Customer;
import com.mytechstore.faq.ingestion.model.CustomerRepository;
import com.mytechstore.faq.ingestion.model.Document;
import com.mytechstore.faq.ingestion.model.DocumentRepository;
import com.mytechstore.faq.ingestion.parser.DocumentParserFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * RAG (Retrieval Augmented Generation) Service
 *
 * Core service orchestrating the document indexing and querying pipeline:
 *
 * 1. INDEXING PIPELINE (Upload → Parse → Chunk → Embed → Store)
 *    - Receive document upload
 *    - Extract text via appropriate parser
 *    - Auto-detect document structure
 *    - Split into semantic chunks
 *    - Generate embeddings via OpenAI
 *    - Store in ChromaDB with metadata
 *
 * 2. QUERY PIPELINE (Query → Search → Rerank → Generate)
 *    - Accept customer-scoped query
 *    - Search ChromaDB for relevant chunks
 *    - Optional re-ranking
 *    - Generate answer via LLM
 *    - Return results with sources
 *
 * Design Patterns Used:
 * - Strategy Pattern: Document parsing via factory
 * - Pipeline Pattern: Sequential processing stages
 * - Repository Pattern: Data abstraction
 */
@Slf4j
@Service
public class RagService {

    private final ChromaDBService chromadbService;
    private final Neo4jGraphService neo4jGraphService;
    private final DocumentParserFactory parserFactory;
    private final CustomerRepository customerRepository;
    private final DocumentRepository documentRepository;
    private final ChatClient chatClient;

    @Value("${app.document.upload-dir}")
    private String uploadDir;

    @Value("${app.document.chunk-size}")
    private int chunkSize;

    @Value("${app.document.chunk-overlap}")
    private int chunkOverlap;

    @Value("${app.rag.top-k-results}")
    private int topK;

    @Value("${app.rag.similarity-threshold}")
    private double similarityThreshold;

    public RagService(
        ChromaDBService chromadbService,
        Neo4jGraphService neo4jGraphService,
        DocumentParserFactory parserFactory,
        CustomerRepository customerRepository,
        DocumentRepository documentRepository,
        ChatClient.Builder chatClientBuilder
    ) {
        this.chromadbService = chromadbService;
        this.neo4jGraphService = neo4jGraphService;
        this.parserFactory = parserFactory;
        this.customerRepository = customerRepository;
        this.documentRepository = documentRepository;
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * INDEXING PIPELINE: Upload and index a document
     *
     * Process Flow:
     * 1. Validate customer exists
     * 2. Create/get collection for customer
     * 3. Parse document
     * 4. Auto-detect structure
     * 5. Chunk document
     * 6. Generate embeddings
     * 7. Store in ChromaDB
     * 8. Update metadata
     *
     * @param customerId Customer identifier
     * @param file Uploaded document file
     * @return Document metadata and processing status
     */
    public DocumentResponse indexDocument(String customerId, MultipartFile file) throws IOException {
        log.info("Starting document indexing for customer: {}", customerId);

        // 1. Validate customer
        Customer customer = customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        // 2. Validate file
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        if (!parserFactory.isSupported(file.getOriginalFilename())) {
            throw new IllegalArgumentException("Unsupported file type: " + file.getOriginalFilename());
        }

        // 3. Create document record
        Document document = Document.builder()
            .customerId(customer.getId())
            .originalFileName(file.getOriginalFilename())
            .fileType(parserFactory.detectFileType(file.getOriginalFilename()))
            .fileSizeBytes(file.getSize())
            .processingStatus(Document.ProcessingStatus.UPLOADED)
            .build();

        document = documentRepository.save(document);
        log.info("Created document record: {} for customer: {}", document.getId(), customerId);

        try {
            // 4. Parse document
            document.setProcessingStatus(Document.ProcessingStatus.EXTRACTING);
            documentRepository.save(document);

            String rawText = parserFactory.extractText(file);
            document.setRawExtractedText(rawText);
            log.info("Extracted text from document: {} chars", rawText.length());

            // 5. Auto-detect structure
            Document.DocumentStructure structure = detectDocumentStructure(rawText);
            document.setDetectedStructure(structure);
            log.info("Detected document structure: {}", structure);

            // 6. Chunk document
            document.setProcessingStatus(Document.ProcessingStatus.CHUNKING);
            documentRepository.save(document);

            List<DocumentChunk> chunks = chunkDocument(rawText, document.getId());
            document.setChunkCount(chunks.size());
            log.info("Created {} chunks from document", chunks.size());

            // 7. Ensure customer collection exists in ChromaDB
            String collectionName = customer.getCollectionName();
            if (collectionName == null || chromadbService.getCollection(collectionName) == null) {
                Map<String, Object> collection = chromadbService.createCollection(customerId, collectionName);
                if (collection == null) {
                    throw new IllegalStateException("Failed to create or resolve customer collection in ChromaDB");
                }
                collectionName = (String) collection.get("name");
                if (collectionName == null || collectionName.isBlank()) {
                    throw new IllegalStateException("ChromaDB collection response missing 'name'");
                }
                customer.setCollectionName(collectionName);
                customerRepository.save(customer);
            }

            // 8. Index chunks in ChromaDB
            document.setProcessingStatus(Document.ProcessingStatus.EMBEDDING);
            documentRepository.save(document);

            indexChunksInChromaDB(collectionName, chunks, customerId, document.getOriginalFileName());

            // 8b. Index FAQ entries in Neo4j for Graph RAG
            try {
                int graphEntries = neo4jGraphService.indexFaqEntries(rawText, customerId);
                if (graphEntries > 0) {
                    log.info("Indexed {} FAQ entries in Neo4j graph for customer {}", graphEntries, customerId);
                }
            } catch (Exception e) {
                log.warn("Neo4j graph indexing failed (non-fatal): {}", e.getMessage());
            }

            // 9. Update document status
            document.setProcessingStatus(Document.ProcessingStatus.COMPLETED);
            document.setIndexedChunkCount(chunks.size());
            document.setIndexedAt(LocalDateTime.now());
            document = documentRepository.save(document);

            // 10. Update customer stats
            customer.setDocumentCount(customer.getDocumentCount() + 1);
            customer.setIndexedChunksCount(
                customerRepository.findByCustomerId(customerId)
                    .map(c -> documentRepository.sumIndexedChunksForCustomer(c.getId()))
                    .orElse(0L)
            );
            customerRepository.save(customer);

            log.info("Successfully indexed document: {} with {} chunks", document.getId(), chunks.size());
            return mapToDocumentResponse(document);

        } catch (Exception e) {
            log.error("Error indexing document: {}", document.getId(), e);
            document.setProcessingStatus(Document.ProcessingStatus.FAILED);
            document.setProcessingError(e.getMessage());
            documentRepository.save(document);
            throw e;
        }
    }

    /**
     * INDEXING PIPELINE: Upload and index with automatic customer detection.
     *
     * Steps:
     * 1. Validate file and extract text
     * 2. Detect customer name via LLM and fallback heuristics
     * 3. Resolve existing customer or create new one
     * 4. Reuse standard indexing pipeline
     */
    public AutoCustomerUploadResponse indexDocumentAutoCustomer(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        if (!parserFactory.isSupported(file.getOriginalFilename())) {
            throw new IllegalArgumentException("Unsupported file type: " + file.getOriginalFilename());
        }

        String extractedText = parserFactory.extractText(file);
        CustomerDetection detection = detectCustomerFromDocument(file.getOriginalFilename(), extractedText);

        Customer customer = findExistingCustomer(detection);
        boolean created = false;

        if (customer == null) {
            customer = Customer.builder()
                .customerId(detection.customerId())
                .name(detection.customerName())
                .description("Auto-created from uploaded FAQ document")
                .contactEmail(null)
                .isActive(true)
                .documentCount(0)
                .indexedChunksCount(0L)
                .build();
            customer = customerRepository.save(customer);
            created = true;
            log.info("Auto-created customer {} ({}) from uploaded document", customer.getName(), customer.getCustomerId());
        }

        DocumentResponse documentResponse = indexDocument(customer.getCustomerId(), file);

        return AutoCustomerUploadResponse.builder()
            .customerId(customer.getCustomerId())
            .customerName(customer.getName())
            .customerCreated(created)
            .detectionSource(detection.source())
            .document(documentResponse)
            .build();
    }

    /**
     * QUERY PIPELINE: Answer a question using RAG
     *
     * Process Flow:
     * 1. Validate customer
     * 2. Search ChromaDB for relevant chunks
     * 3. Rerank results (optional)
     * 4. Generate answer via LLM with context
     * 5. Return answer with source attribution
     *
     * @param request Query request with customer context
     * @return Answer with source documents
     */
    public FaqQueryResponse queryFaq(FaqQueryRequest request) {
        log.info("Processing FAQ query for customer: {}", request.getCustomerId());

        try {
            // 1. Validate customer
            Customer customer = customerRepository.findByCustomerId(request.getCustomerId())
                .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));

            if (customer.getCollectionName() == null) {
                log.warn("No collection for customer: {}", request.getCustomerId());
                return FaqQueryResponse.builder()
                    .question(request.getQuestion())
                    .answer("No documents indexed for this customer yet.")
                    .sourceResults(new SearchResultResponse[0])
                    .totalSourcesUsed(0)
                    .averageSimilarity(0.0)
                    .build();
            }

            int effectiveTopK = request.getTopK() != null ? request.getTopK() : topK;
            double effectiveThreshold = request.getSimilarityThreshold() != null ?
                request.getSimilarityThreshold() : similarityThreshold;

            // 2. Search ChromaDB
            log.debug("Querying ChromaDB collection: {} with topK={}, threshold={}", 
                customer.getCollectionName(), effectiveTopK, effectiveThreshold);
            
            Map<String, Object> searchResults = chromadbService.query(
                customer.getCollectionName(),
                Collections.singletonList(request.getQuestion()),
                effectiveTopK,
                null
            );

            List<SearchResultResponse> results = parseSearchResults(searchResults, effectiveThreshold);

            if (results.isEmpty()) {
                log.info("No relevant results found for question: {}", request.getQuestion());
                return FaqQueryResponse.builder()
                    .question(request.getQuestion())
                    .answer("No relevant information found in the FAQ documents.")
                    .sourceResults(new SearchResultResponse[0])
                    .totalSourcesUsed(0)
                    .averageSimilarity(0.0)
                    .build();
            }

            // 3. Generate answer with LLM
            String context = buildContextFromResults(results);
            String answer = generateAnswerWithLLM(request.getQuestion(), context);

            // 4. Calculate metrics
            double avgSimilarity = results.stream()
                .mapToDouble(SearchResultResponse::getSimilarityScore)
                .average()
                .orElse(0.0);

            log.info("Generated answer using {} sources with avg similarity: {}", results.size(), avgSimilarity);

            return FaqQueryResponse.builder()
                .question(request.getQuestion())
                .answer(answer)
                .sourceResults(results.toArray(new SearchResultResponse[0]))
                .totalSourcesUsed(results.size())
                .averageSimilarity(avgSimilarity)
                .build();
                
        } catch (IllegalArgumentException e) {
            log.error("Invalid query request: {}", e.getMessage());
            return FaqQueryResponse.builder()
                .question(request.getQuestion())
                .answer("Error: " + e.getMessage())
                .sourceResults(new SearchResultResponse[0])
                .totalSourcesUsed(0)
                .averageSimilarity(0.0)
                .build();
        } catch (Exception e) {
            log.error("Error processing FAQ query", e);
            return FaqQueryResponse.builder()
                .question(request.getQuestion())
                .answer("Service error: Unable to process query at this time. Please try again later.")
                .sourceResults(new SearchResultResponse[0])
                .totalSourcesUsed(0)
                .averageSimilarity(0.0)
                .build();
        }
    }

    /**
     * List indexed documents for a customer ordered by newest first.
     *
     * @param customerId Customer identifier
     * @return Document metadata list
     */
    public List<DocumentResponse> listDocumentsForCustomer(String customerId) {
        log.info("Listing indexed documents for customer: {}", customerId);

        Customer customer = customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        return documentRepository.findByCustomerIdOrderByCreatedAtDesc(customer.getId())
            .stream()
            .map(this::mapToDocumentResponse)
            .toList();
    }

    // ============ Helper Methods ============

    /**
     * Auto-detect document structure using LLM
     */
    private Document.DocumentStructure detectDocumentStructure(String text) {
        // Simplified heuristics - can be enhanced with LLM analysis
        if (text.contains("Q:") && text.contains("A:")) {
            return Document.DocumentStructure.STRUCTURED;
        } else if (text.contains("#") || text.contains("##")) {
            return Document.DocumentStructure.MARKDOWN_BASED;
        } else if (text.contains("---")) {
            return Document.DocumentStructure.YAML_BASED;
        }
        return Document.DocumentStructure.UNSTRUCTURED;
    }

    /**
     * Split document into semantic chunks
     */
    private List<DocumentChunk> chunkDocument(String text, Long documentId) {
        List<DocumentChunk> chunks = new ArrayList<>();
        int chunkIndex = 0;

        for (int i = 0; i < text.length(); i += chunkSize - chunkOverlap) {
            int end = Math.min(i + chunkSize, text.length());
            String chunkText = text.substring(i, end).trim();

            if (!chunkText.isEmpty()) {
                chunks.add(new DocumentChunk(
                    "doc_" + documentId + "_chunk_" + chunkIndex,
                    chunkText,
                    chunkIndex
                ));
                chunkIndex++;
            }

            if (end >= text.length()) break;
        }

        return chunks;
    }

    /**
     * Index chunks in ChromaDB
     */
    private void indexChunksInChromaDB(String collectionName, List<DocumentChunk> chunks,
                                       String customerId, String documentName) {
        List<String> ids = new ArrayList<>();
        List<String> documents = new ArrayList<>();
        List<Map<String, Object>> metadatas = new ArrayList<>();

        for (DocumentChunk chunk : chunks) {
            ids.add(chunk.getId());
            documents.add(chunk.getText());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("customer_id", customerId);
            metadata.put("document_name", documentName);
            metadata.put("chunk_number", chunk.getChunkIndex());
            metadata.put("indexed_at", System.currentTimeMillis());
            metadatas.add(metadata);
        }

        boolean indexed = chromadbService.addDocuments(collectionName, documents, metadatas, ids);
        if (!indexed) {
            throw new IllegalStateException("Failed to add chunk embeddings/documents to ChromaDB");
        }
    }

    /**
     * Parse ChromaDB search results
     * 
     * ChromaDB v2 returns:
     * {
     *   "distances": [[d1, d2, ...]], (distance scores [0-2])
     *   "documents": [[doc1, doc2, ...]], (retrieved text)
     *   "metadatas": [[{...}, {...}, ...]], (chunk metadata)
     *   "ids": [[id1, id2, ...]] (chunk ids)
     * }
     * 
     * Similarity = 1.0 - (distance / 2.0)
     */
    private List<SearchResultResponse> parseSearchResults(Map<String, Object> searchResults,
                                                          double threshold) {
        List<SearchResultResponse> results = new ArrayList<>();

        try {
            @SuppressWarnings("unchecked")
            List<List<Double>> distances = (List<List<Double>>) searchResults.get("distances");
            @SuppressWarnings("unchecked")
            List<List<String>> documents = (List<List<String>>) searchResults.get("documents");
            @SuppressWarnings("unchecked")
            List<List<Map<String, Object>>> metadatas = 
                (List<List<Map<String, Object>>>) searchResults.get("metadatas");

            if (distances == null || documents == null || distances.isEmpty() || documents.isEmpty()) {
                log.debug("No results from ChromaDB query");
                return results;
            }

            List<Double> queryDistances = distances.get(0);
            List<String> queryDocuments = documents.get(0);
            List<Map<String, Object>> queryMetadatas = (metadatas != null && !metadatas.isEmpty()) 
                ? metadatas.get(0) 
                : new ArrayList<>();

            // Parse each result
            for (int i = 0; i < queryDocuments.size(); i++) {
                double distance = queryDistances.get(i);
                double similarity = 1.0 - (distance / 2.0); // Normalize distance to similarity

                // Skip results below threshold
                if (similarity < threshold) {
                    log.debug("Skipping result with similarity {} < threshold {}", similarity, threshold);
                    continue;
                }

                String content = queryDocuments.get(i);
                Map<String, Object> metadata = i < queryMetadatas.size() 
                    ? queryMetadatas.get(i) 
                    : new HashMap<>();

                SearchResultResponse result = SearchResultResponse.builder()
                    .content(content)
                    .similarityScore(similarity)
                    .sourceDocument((String) metadata.getOrDefault("document_name", "Unknown"))
                    .chunkNumber(((Number) metadata.getOrDefault("chunk_number", 0)).intValue())
                    .metadata(metadata.toString())
                    .build();

                results.add(result);
                log.debug("Added search result: {} from {}", i, result.getSourceDocument());
            }

            log.info("Parsed {} search results from ChromaDB", results.size());
        } catch (Exception e) {
            log.error("Error parsing ChromaDB search results", e);
        }

        return results;
    }

    /**
     * Build context string from search results
     */
    private String buildContextFromResults(List<SearchResultResponse> results) {
        StringBuilder context = new StringBuilder();
        for (SearchResultResponse result : results) {
            context.append(result.getContent()).append("\n\n");
        }
        return context.toString();
    }

    /**
     * Generate answer using OpenAI LLM
     */
    private String generateAnswerWithLLM(String question, String context) {
        String prompt = String.format(
            "Based on the following context, please answer the question.\n\n" +
            "Context:\n%s\n\n" +
            "Question: %s\n\n" +
            "Answer:",
            context, question
        );

        return chatClient.prompt()
            .user(prompt)
            .call()
            .content();
    }

    /**
     * Helper class for document chunks
     */
    static class DocumentChunk {
        String id;
        String text;
        int chunkIndex;

        DocumentChunk(String id, String text, int chunkIndex) {
            this.id = id;
            this.text = text;
            this.chunkIndex = chunkIndex;
        }

        public String getId() { return id; }
        public String getText() { return text; }
        public int getChunkIndex() { return chunkIndex; }
    }

    /**
     * Map Document entity to DTO
     */
    private DocumentResponse mapToDocumentResponse(Document doc) {
        return DocumentResponse.builder()
            .id(doc.getId())
            .customerId(doc.getCustomerId())
            .originalFileName(doc.getOriginalFileName())
            .fileType(doc.getFileType())
            .fileSizeBytes(doc.getFileSizeBytes())
            .processingStatus(doc.getProcessingStatus())
            .detectedStructure(doc.getDetectedStructure())
            .autoDetectionNotes(doc.getAutoDetectionNotes())
            .chunkCount(doc.getChunkCount())
            .indexedChunkCount(doc.getIndexedChunkCount())
            .createdAt(doc.getCreatedAt())
            .indexedAt(doc.getIndexedAt())
            .build();
    }

    private Customer findExistingCustomer(CustomerDetection detection) {
        Optional<Customer> exact = customerRepository.findByCustomerId(detection.customerId());
        if (exact.isPresent()) {
            return exact.get();
        }

        return customerRepository.findAll()
            .stream()
            .filter(existing -> existing.getCustomerId() != null && existing.getCustomerId().equalsIgnoreCase(detection.customerId()))
            .findFirst()
            .orElseGet(() -> customerRepository.findAll()
                .stream()
                .filter(existing -> existing.getName() != null && existing.getName().equalsIgnoreCase(detection.customerName()))
                .findFirst()
                .orElse(null));
    }

    private CustomerDetection detectCustomerFromDocument(String fileName, String text) {
        String heuristicName = inferCustomerNameFromFilename(fileName);
        String llmName = inferCustomerNameFromLlm(text, heuristicName);

        String chosenName = isUsefulCustomerName(llmName)
            ? llmName.trim()
            : (isUsefulCustomerName(heuristicName) ? heuristicName.trim() : "General Customer");

        String customerId = toCustomerId(chosenName);
        String normalizedName = normalizeDisplayName(chosenName);
        String source = isUsefulCustomerName(llmName) ? "llm" : "filename-heuristic";

        return new CustomerDetection(customerId, normalizedName, source);
    }

    private String inferCustomerNameFromLlm(String text, String fallback) {
        try {
            String snippet = text == null ? "" : text.substring(0, Math.min(2500, text.length()));
            String prompt = "Identify the company or customer name in this FAQ document. "
                + "Return only the name and nothing else. "
                + "If no clear name exists, return UNKNOWN.\n\n"
                + "Fallback hint: " + (fallback == null ? "UNKNOWN" : fallback) + "\n\n"
                + "Document:\n" + snippet;

            String response = chatClient.prompt().user(prompt).call().content();
            if (response == null) {
                return null;
            }

            return response
                .replace("\"", "")
                .replace("'", "")
                .replace("`", "")
                .replace("\n", " ")
                .trim();
        } catch (Exception e) {
            log.debug("LLM customer detection failed, fallback to filename heuristic", e);
            return null;
        }
    }

    private String inferCustomerNameFromFilename(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }

        String base = fileName;
        int dotIndex = base.lastIndexOf('.');
        if (dotIndex > 0) {
            base = base.substring(0, dotIndex);
        }

        String cleaned = base
            .replaceAll("(?i)faq", " ")
            .replaceAll("(?i)knowledge.?base", " ")
            .replaceAll("(?i)policy", " ")
            .replaceAll("[_\\-]+", " ")
            .trim();

        return cleaned.isBlank() ? null : cleaned;
    }

    private boolean isUsefulCustomerName(String value) {
        if (value == null) {
            return false;
        }

        String trimmed = value.trim();
        if (trimmed.isBlank()) {
            return false;
        }

        String lowered = trimmed.toLowerCase(Locale.ROOT);
        return !lowered.equals("unknown")
            && !lowered.equals("n/a")
            && !lowered.equals("none")
            && !lowered.equals("general customer");
    }

    private String normalizeDisplayName(String rawName) {
        String cleaned = rawName == null ? "General Customer" : rawName.trim();
        if (cleaned.isBlank()) {
            cleaned = "General Customer";
        }
        return cleaned;
    }

    private String toCustomerId(String value) {
        String cleaned = value == null ? "general_customer" : value.toLowerCase(Locale.ROOT);
        cleaned = Pattern.compile("[^a-z0-9]+")
            .matcher(cleaned)
            .replaceAll("_")
            .replaceAll("^_+|_+$", "");

        if (cleaned.isBlank()) {
            return "general_customer";
        }
        return cleaned;
    }

    private record CustomerDetection(String customerId, String customerName, String source) {}

}
