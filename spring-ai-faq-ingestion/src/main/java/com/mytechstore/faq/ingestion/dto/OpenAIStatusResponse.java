package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpenAIStatusResponse {
    private Boolean configured;
    private String embeddingModel;
    private String llmModel;
}