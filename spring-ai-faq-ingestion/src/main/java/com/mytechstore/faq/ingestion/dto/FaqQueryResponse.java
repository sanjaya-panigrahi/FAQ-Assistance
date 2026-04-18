package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaqQueryResponse {
    private String question;
    private String answer;
    private SearchResultResponse[] sourceResults;
    private Integer totalSourcesUsed;
    private Double averageSimilarity;
}