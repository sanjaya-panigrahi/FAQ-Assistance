package com.mytechstore.faq.ingestion.service;

import com.mytechstore.faq.ingestion.dto.CustomerCreateRequest;
import com.mytechstore.faq.ingestion.dto.CustomerResponse;
import com.mytechstore.faq.ingestion.model.Customer;
import com.mytechstore.faq.ingestion.model.CustomerRepository;
import com.mytechstore.faq.ingestion.model.DocumentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Customer Service
 *
 * Manages multi-tenant customer operations:
 * - Create new customers
 * - List all customers
 * - Get customer details
 * - Update customer info
 * - Delete customers
 *
 * Multi-Tenancy Isolation:
 * - Each customer has isolated ChromaDB collection
 * - Queries are always filtered by customer context
 * - Document uploads are customer-scoped
 */
@Slf4j
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final DocumentRepository documentRepository;
    private final ChromaDBService chromadbService;

    public CustomerService(
        CustomerRepository customerRepository,
        DocumentRepository documentRepository,
        ChromaDBService chromadbService
    ) {
        this.customerRepository = customerRepository;
        this.documentRepository = documentRepository;
        this.chromadbService = chromadbService;
    }

    /**
     * Create a new customer
     * @param request Customer creation details
     * @return Created customer response
     */
    @Transactional
    public CustomerResponse createCustomer(CustomerCreateRequest request) {
        log.info("Creating new customer: {}", request.getCustomerId());

        // Validate unique customer ID
        if (customerRepository.existsByCustomerId(request.getCustomerId())) {
            throw new IllegalArgumentException("Customer ID already exists: " + request.getCustomerId());
        }

        // Create customer
        Customer customer = Customer.builder()
            .customerId(request.getCustomerId())
            .name(request.getName())
            .description(request.getDescription())
            .contactEmail(request.getContactEmail())
            .isActive(true)
            .documentCount(0)
            .indexedChunksCount(0L)
            .build();

        customer = customerRepository.save(customer);
        log.info("Created customer: {} with ID: {}", customer.getName(), customer.getId());

        return mapToResponse(customer);
    }

    /**
     * Get customer by ID
     * @param customerId Customer identifier
     * @return Customer response
     */
    public CustomerResponse getCustomer(String customerId) {
        Customer customer = customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        return mapToResponse(customer);
    }

    /**
     * List all customers
     * @return List of customer responses
     */
    public List<CustomerResponse> listCustomers() {
        return customerRepository.findByIsActiveTrue()
            .stream()
            .map(this::mapToResponse)
            .collect(Collectors.toList());
    }

    /**
     * Update customer
     * @param customerId Customer identifier
     * @param request Update details
     * @return Updated customer response
     */
    @Transactional
    public CustomerResponse updateCustomer(String customerId, CustomerCreateRequest request) {
        log.info("Updating customer: {}", customerId);

        Customer customer = customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        if (request.getName() != null) {
            customer.setName(request.getName());
        }
        if (request.getDescription() != null) {
            customer.setDescription(request.getDescription());
        }
        if (request.getContactEmail() != null) {
            customer.setContactEmail(request.getContactEmail());
        }

        customer.setUpdatedAt(LocalDateTime.now());
        customer = customerRepository.save(customer);

        log.info("Updated customer: {}", customerId);
        return mapToResponse(customer);
    }

    /**
     * Delete customer and associated data
     * @param customerId Customer identifier
     */
    @Transactional
    public void deleteCustomer(String customerId) {
        log.info("Deleting customer: {}", customerId);

        Customer customer = customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        // Delete ChromaDB collection
        if (customer.getCollectionName() != null) {
            chromadbService.deleteCollection(customer.getCollectionName());
            log.info("Deleted ChromaDB collection: {}", customer.getCollectionName());
        }

        // Delete documents
        documentRepository.findByCustomerId(customer.getId())
            .forEach(doc -> {
                documentRepository.delete(doc);
                log.debug("Deleted document: {}", doc.getId());
            });

        // Delete customer
        customerRepository.delete(customer);
        log.info("Deleted customer: {}", customerId);
    }

    /**
     * Get customer statistics
     * @param customerId Customer identifier
     * @return Stats map
     */
    public java.util.Map<String, Object> getCustomerStats(String customerId) {
        Customer customer = customerRepository.findByCustomerId(customerId)
            .orElseThrow(() -> new IllegalArgumentException("Customer not found: " + customerId));

        long documentCount = documentRepository.countByCustomerId(customer.getId());
        long indexedChunks = documentRepository.sumIndexedChunksForCustomer(customer.getId());

        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        stats.put("customerId", customerId);
        stats.put("name", customer.getName());
        stats.put("documentCount", documentCount);
        stats.put("indexedChunksCount", indexedChunks);
        stats.put("collectionName", customer.getCollectionName());
        stats.put("isActive", customer.getIsActive());
        stats.put("createdAt", customer.getCreatedAt());
        stats.put("updatedAt", customer.getUpdatedAt());

        return stats;
    }

    /**
     * Map Customer entity to Response DTO
     */
    private CustomerResponse mapToResponse(Customer customer) {
        return CustomerResponse.builder()
            .id(customer.getId())
            .customerId(customer.getCustomerId())
            .name(customer.getName())
            .description(customer.getDescription())
            .contactEmail(customer.getContactEmail())
            .isActive(customer.getIsActive())
            .documentCount(customer.getDocumentCount())
            .indexedChunksCount(customer.getIndexedChunksCount())
            .collectionName(customer.getCollectionName())
            .createdAt(customer.getCreatedAt())
            .updatedAt(customer.getUpdatedAt())
            .build();
    }

}
