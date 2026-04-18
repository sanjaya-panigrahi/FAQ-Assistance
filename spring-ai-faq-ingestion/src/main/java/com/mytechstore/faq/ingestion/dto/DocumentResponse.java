package com.mytechstore.faq.ingestion.dto;

import com.mytechstore.faq.ingestion.model.Document;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DocumentResponse {
    private Long id;
    private Long customerId;
    private String originalFileName;
    private String fileType;
    private Long fileSizeBytes;
    private Document.ProcessingStatus processingStatus;
    private Document.DocumentStructure detectedStructure;
    private String autoDetectionNotes;
    private Integer chunkCount;
    private Integer indexedChunkCount;
    private String processingError;
    private LocalDateTime createdAt;
    private LocalDateTime indexedAt;
}