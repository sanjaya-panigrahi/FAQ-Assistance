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
        DocumentParserFactory parserFactory,
        CustomerRepository customerRepository,
        DocumentRepository documentRepository,
        ChatClient.Builder chatClientBuilder
    ) {
        this.chromadbService = chromadbService;
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

            // 7. Ensure customer collection exists
            String collectionName = customer.getCollectionName();
            if (collectionName == null) {
                Map<String, Object> collection = chromadbService.createCollection(customerId, null);
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

        // 1. Validate customer
        Customer customer = customerRepository.findByCustomerId(request.getCustomerId())
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + request.getCustomerId()));

        if (customer.getCollectionName() == null) {
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
        Map<String, Object> searchResults = chromadbService.query(
            customer.getCollectionName(),
            Collections.singletonList(request.getQuestion()),
            effectiveTopK,
            null
        );

        List<SearchResultResponse> results = parseSearchResults(searchResults, effectiveThreshold);

        if (results.isEmpty()) {
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

        log.info("Generated answer using {} sources", results.size());

        return FaqQueryResponse.builder()
            .question(request.getQuestion())
            .answer(answer)
            .sourceResults(results.toArray(new SearchResultResponse[0]))
            .totalSourcesUsed(results.size())
            .averageSimilarity(avgSimilarity)
            .build();
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
     */
    private List<SearchResultResponse> parseSearchResults(Map<String, Object> searchResults,
                                                          double threshold) {
        List<SearchResultResponse> results = new ArrayList<>();

        // Parse ChromaDB response structure
        Object distances = searchResults.get("distances");
        Object documents = searchResults.get("documents");
        Object metadatas = searchResults.get("metadatas");

        // Implementation depends on ChromaDB response format
        // This is simplified placeholder

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

}
