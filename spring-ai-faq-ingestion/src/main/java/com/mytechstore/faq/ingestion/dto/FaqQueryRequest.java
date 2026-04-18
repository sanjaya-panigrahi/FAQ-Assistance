package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaqQueryRequest {
    private String customerId;
    private String question;
    private Integer topK;
    private Double similarityThreshold;
}