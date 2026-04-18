package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerResponse {
    private Long id;
    private String customerId;
    private String name;
    private String description;
    private String contactEmail;
    private Boolean isActive;
    private Integer documentCount;
    private Long indexedChunksCount;
    private String collectionName;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}