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
public class DocumentProcessingProgressResponse {
    private Long documentId;
    private Document.ProcessingStatus status;
    private Integer totalChunks;
    private Integer processedChunks;
    private Integer failedChunks;
    private String message;
    private LocalDateTime lastUpdated;
}