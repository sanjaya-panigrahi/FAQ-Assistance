package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchResultResponse {
    private String content;
    private Double similarityScore;
    private String sourceDocument;
    private Integer chunkNumber;
    private String metadata;
}