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
 * Document Entity - Represents an uploaded FAQ document
 *
 * Tracks:
 * - Document metadata (name, type, size)
 * - Processing status (uploaded, processing, indexed, failed)
 * - Structure detection results (auto-detected document type)
 * - Chunk statistics
 * - Association with customer (multi-tenancy)
 */
@Entity
@Table(name = "documents", indexes = {
    @Index(name = "idx_customer_id", columnList = "customer_id"),
    @Index(name = "idx_status", columnList = "processing_status"),
    @Index(name = "idx_created_at", columnList = "created_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;  // Foreign key to Customer

    @Column(nullable = false, length = 255)
    private String originalFileName;  // Original uploaded filename

    @Column(nullable = false, length = 100)
    private String fileType;  // pdf, md, yaml, docx, txt, image

    @Column(nullable = false)
    private Long fileSizeBytes;

    @Column(name = "file_path", length = 1000)
    private String filePath;  // Path where file is stored

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private ProcessingStatus processingStatus = ProcessingStatus.UPLOADED;

    @Enumerated(EnumType.STRING)
    private DocumentStructure detectedStructure = DocumentStructure.UNSTRUCTURED;

    @Column(length = 1000)
    private String autoDetectionNotes;  // LLM analysis notes on document structure

    @Column(name = "chunk_count")
    private Integer chunkCount = 0;

    @Column(name = "indexed_chunk_count")
    private Integer indexedChunkCount = 0;

    @Column(length = 2000)
    private String processingError;  // Error message if processing failed

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "indexed_at")
    private LocalDateTime indexedAt;

    @Column(name = "raw_text", columnDefinition = "CLOB")
    private String rawExtractedText;  // Full text extracted from document

    /**
     * Processing Status enum
     */
    public enum ProcessingStatus {
        UPLOADED,      // File received, awaiting processing
        EXTRACTING,    // Text extraction in progress
        CHUNKING,      // Document being split into chunks
        EMBEDDING,     // Embeddings being generated
        INDEXING,      // Storing in ChromaDB
        COMPLETED,     // Successfully indexed
        FAILED         // Processing failed
    }

    /**
     * Document Structure enum - Auto-detected by LLM
     */
    public enum DocumentStructure {
        STRUCTURED,     // Clearly defined FAQ format (Q&A pairs, sections)
        SEMI_STRUCTURED,// Has some structure but mixed content
        UNSTRUCTURED,   // Free-form text, mixed content
        YAML_BASED,     // YAML formatted
        MARKDOWN_BASED, // Markdown with clear hierarchy
        PDF_TABLE,      // PDF with tables
        IMAGE_TEXT      // Text extracted from images
    }

}
