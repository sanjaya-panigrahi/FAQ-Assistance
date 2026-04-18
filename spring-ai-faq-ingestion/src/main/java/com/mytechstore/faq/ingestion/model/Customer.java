package com.mytechstore.faq.ingestion.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Customer Entity - Represents a customer organization
 *
 * Multi-tenant Strategy:
 * - Each customer has isolated FAQ documents
 * - Customer ID serves as tenant identifier
 * - All queries are filtered by customer context
 * - ChromaDB collections are prefixed with customer ID
 */
@Entity
@Table(name = "customers")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Customer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String customerId;  // Unique identifier like "acme_corp"

    @Column(nullable = false, length = 255)
    private String name;  // Display name like "Acme Corporation"

    @Column(length = 1000)
    private String description;  // Customer description

    @Column(name = "contact_email", length = 255)
    private String contactEmail;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "collection_name")
    private String collectionName;  // ChromaDB collection name (e.g., "faq_acme_corp")

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "document_count")
    private Integer documentCount = 0;

    @Column(name = "indexed_chunks_count")
    private Long indexedChunksCount = 0L;

}
