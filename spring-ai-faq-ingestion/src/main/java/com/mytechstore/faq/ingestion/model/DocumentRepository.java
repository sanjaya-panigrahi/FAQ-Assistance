package com.mytechstore.faq.ingestion.model;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Document entity
 * Provides data access operations for document metadata management
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * Find all documents for a specific customer
     * @param customerId The customer ID
     * @return List of documents
     */
    List<Document> findByCustomerId(Long customerId);

    /**
     * Find all documents for a specific customer ordered by newest first
     * @param customerId The customer ID
     * @return Ordered list of documents
     */
    List<Document> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    /**
     * Find documents by processing status
     * @param customerId The customer ID
     * @param status The processing status
     * @return List of documents with specified status
     */
    List<Document> findByCustomerIdAndProcessingStatus(Long customerId, Document.ProcessingStatus status);

    /**
     * Find all documents awaiting processing
     * @return List of documents not yet completed or failed
     */
    @Query("SELECT d FROM Document d WHERE d.processingStatus NOT IN " +
           "(com.mytechstore.faq.ingestion.model.Document.ProcessingStatus.COMPLETED, " +
           "com.mytechstore.faq.ingestion.model.Document.ProcessingStatus.FAILED)")
    List<Document> findPendingDocuments();

    /**
     * Count total documents for a customer
     * @param customerId The customer ID
     * @return Document count
     */
    long countByCustomerId(Long customerId);

    /**
     * Count indexed chunks across all documents for a customer
     * @param customerId The customer ID
     * @return Total indexed chunks
     */
    @Query("SELECT COALESCE(SUM(d.indexedChunkCount), 0) FROM Document d WHERE d.customerId = :customerId")
    long sumIndexedChunksForCustomer(@Param("customerId") Long customerId);

}
