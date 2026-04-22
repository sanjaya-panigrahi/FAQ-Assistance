package com.mytechstore.faq.ingestion.controller;

import com.mytechstore.faq.ingestion.dto.*;
import com.mytechstore.faq.ingestion.service.CustomerService;
import com.mytechstore.faq.ingestion.service.RagService;
import com.mytechstore.faq.ingestion.service.ChromaDBService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.view.RedirectView;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FAQ Ingestion REST API Controller
 *
 * Exposes endpoints for:
 * - Multi-tenant customer management
 * - FAQ document upload and indexing
 * - FAQ query with RAG
 * - System health and status
 *
 * API Base Path: /api/faq-ingestion
 */
@Slf4j
@RestController
@RequestMapping("/api/faq-ingestion")
@CrossOrigin(origins = "*", maxAge = 3600)
public class FaqIngestionController {

    private final CustomerService customerService;
    private final RagService ragService;
    private final ChromaDBService chromadbService;

    @Value("${app.chroma.persist-directory}")
    private String chromaPersistDir;

    public FaqIngestionController(
        CustomerService customerService,
        RagService ragService,
        ChromaDBService chromadbService
    ) {
        this.customerService = customerService;
        this.ragService = ragService;
        this.chromadbService = chromadbService;
    }

    // ============ CUSTOMER MANAGEMENT ENDPOINTS ============

    /**
     * Create a new customer
     * POST /api/faq-ingestion/customers
     */
    @PostMapping("/customers")
    public ResponseEntity<CustomerResponse> createCustomer(@RequestBody CustomerCreateRequest request) {
        log.info("API: Creating customer - {}", request.getCustomerId());
        CustomerResponse response = customerService.createCustomer(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Get all customers
     * GET /api/faq-ingestion/customers
     */
    @GetMapping("/customers")
    public ResponseEntity<List<CustomerResponse>> listCustomers() {
        log.info("API: Listing all customers");
        List<CustomerResponse> customers = customerService.listCustomers();
        return ResponseEntity.ok(customers);
    }

    /**
     * Get specific customer
     * GET /api/faq-ingestion/customers/{customerId}
     */
    @GetMapping("/customers/{customerId}")
    public ResponseEntity<CustomerResponse> getCustomer(@PathVariable String customerId) {
        log.info("API: Getting customer - {}", customerId);
        CustomerResponse customer = customerService.getCustomer(customerId);
        return ResponseEntity.ok(customer);
    }

    /**
     * Update customer
     * PUT /api/faq-ingestion/customers/{customerId}
     */
    @PutMapping("/customers/{customerId}")
    public ResponseEntity<CustomerResponse> updateCustomer(
        @PathVariable String customerId,
        @RequestBody CustomerCreateRequest request
    ) {
        log.info("API: Updating customer - {}", customerId);
        CustomerResponse response = customerService.updateCustomer(customerId, request);
        return ResponseEntity.ok(response);
    }

    /**
     * Delete customer
     * DELETE /api/faq-ingestion/customers/{customerId}
     */
    @DeleteMapping("/customers/{customerId}")
    public ResponseEntity<Void> deleteCustomer(@PathVariable String customerId) {
        log.info("API: Deleting customer - {}", customerId);
        customerService.deleteCustomer(customerId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get customer statistics
     * GET /api/faq-ingestion/customers/{customerId}/stats
     */
    @GetMapping("/customers/{customerId}/stats")
    public ResponseEntity<Map<String, Object>> getCustomerStats(@PathVariable String customerId) {
        log.info("API: Getting customer stats - {}", customerId);
        Map<String, Object> stats = customerService.getCustomerStats(customerId);
        return ResponseEntity.ok(stats);
    }

    // ============ DOCUMENT UPLOAD & INDEXING ENDPOINTS ============

    /**
     * Upload and index FAQ document
     * POST /api/faq-ingestion/documents/upload
     * Form Parameters:
     * - customerId: Customer identifier
     * - file: Document file (multipart)
     */
    @PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
        @RequestParam String customerId,
        @RequestParam MultipartFile file
    ) {
        log.info("API: Uploading document for customer - {} (file: {})", customerId, file.getOriginalFilename());

        try {
            DocumentResponse response = ragService.indexDocument(customerId, file);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IOException e) {
            log.error("Error uploading document", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Upload and index FAQ document with automatic customer detection.
     * POST /api/faq-ingestion/documents/upload/auto-customer
     * Form Parameters:
     * - file: Document file (multipart)
     */
    @PostMapping(value = "/documents/upload/auto-customer", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<AutoCustomerUploadResponse> uploadDocumentAutoCustomer(
        @RequestParam MultipartFile file
    ) {
        log.info("API: Uploading document with auto customer detection (file: {})", file.getOriginalFilename());

        try {
            AutoCustomerUploadResponse response = ragService.indexDocumentAutoCustomer(file);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IOException e) {
            log.error("Error uploading document with auto customer detection", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get documents for a customer
     * GET /api/faq-ingestion/customers/{customerId}/documents
     */
    @GetMapping("/customers/{customerId}/documents")
    public ResponseEntity<List<DocumentResponse>> getCustomerDocuments(
        @PathVariable String customerId
    ) {
        log.info("API: Getting documents for customer - {}", customerId);
        List<DocumentResponse> documents = ragService.listDocumentsForCustomer(customerId);
        return ResponseEntity.ok(documents);
    }

    // ============ QUERY & RAG ENDPOINTS ============

    /**
     * Query FAQ documents with RAG
     * POST /api/faq-ingestion/query
     * Request Body:
     * {
     *   "customerId": "acme_corp",
     *   "question": "What is your return policy?",
     *   "topK": 5,
     *   "similarityThreshold": 0.5
     * }
     */
    @PostMapping("/query")
    public ResponseEntity<FaqQueryResponse> queryFaq(@RequestBody FaqQueryRequest request) {
        log.info("API: FAQ query from customer - {} - Question: {}", 
            request.getCustomerId(), request.getQuestion());

        try {
            FaqQueryResponse response = ragService.queryFaq(request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Error processing FAQ query", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    // ============ HEALTH & MONITORING ENDPOINTS ============

    /**
     * Service health check
     * GET /api/faq-ingestion/health
     */
    @GetMapping("/health")
    public ResponseEntity<ServiceHealthResponse> getHealth() {
        log.debug("API: Health check");

        boolean chromadbHealthy = chromadbService.isHealthy();
        int collectionCount = chromadbHealthy ? chromadbService.getTotalCollectionCount() : -1;

        ServiceHealthResponse response = ServiceHealthResponse.builder()
            .status(chromadbHealthy ? "UP" : "DEGRADED")
            .version("1.0.0")
            .chromadb(ChromaDBStatusResponse.builder()
                .connected(chromadbHealthy)
                .persistDirectory(chromaPersistDir)
                .collectionCount(collectionCount >= 0 ? collectionCount : null)
                .build())
            .openai(OpenAIStatusResponse.builder()
                .configured(true)
                .embeddingModel("text-embedding-3-small")
                .llmModel("gpt-4o-mini")
                .build())
            .database(DatabaseStatusResponse.builder()
                .connected(true)
                .build())
            .build();

        return ResponseEntity.ok(response);
    }

    /**
     * Get service info
     * GET /api/faq-ingestion/info
     */
    @GetMapping("/info")
    public ResponseEntity<Map<String, Object>> getInfo() {
        log.debug("API: Service info request");

        Map<String, Object> info = new HashMap<>();
        info.put("service", "spring-ai-faq-ingestion");
        info.put("version", "1.0.0");
        info.put("description", "Multi-tenant FAQ document ingestion and RAG service");
        info.put("endpoints", Map.of(
            "customers", "/api/faq-ingestion/customers",
            "documents", "/api/faq-ingestion/documents/upload",
            "query", "/api/faq-ingestion/query",
            "health", "/api/faq-ingestion/health",
            "chroma-ui", "/chroma-ui"
        ));
        info.put("supportedFormats", new String[]{"pdf", "md", "yaml", "docx", "txt", "png", "jpg", "jpeg"});

        return ResponseEntity.ok(info);
    }

    /**
     * Redirect to ChromaDB web UI
     * GET /api/faq-ingestion/chroma-ui
     */
    @GetMapping("/chroma-ui")
    public RedirectView redirectToChromaUI() {
        log.info("API: Redirecting to ChromaDB UI");
        return new RedirectView("http://localhost:8000", true);
    }

    // ============ ERROR HANDLERS ============

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException e) {
        log.error("Invalid argument: {}", e.getMessage());
        Map<String, String> error = new HashMap<>();
        error.put("error", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("Unexpected error", e);
        Map<String, String> error = new HashMap<>();
        error.put("error", "Internal server error: " + e.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error);
    }

}
