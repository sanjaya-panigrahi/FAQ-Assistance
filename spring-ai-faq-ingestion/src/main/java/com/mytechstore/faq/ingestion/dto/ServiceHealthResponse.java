package com.mytechstore.faq.ingestion.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceHealthResponse {
    private String status;
    private String version;
    private ChromaDBStatusResponse chromadb;
    private OpenAIStatusResponse openai;
    private DatabaseStatusResponse database;
}