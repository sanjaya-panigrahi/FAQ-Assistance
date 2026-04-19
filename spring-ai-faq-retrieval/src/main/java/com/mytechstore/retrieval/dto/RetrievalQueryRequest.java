package com.mytechstore.retrieval.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RetrievalQueryRequest(
    @Size(max = 100) String tenantId,
    @NotBlank @Size(max = 1000) String question,
    @Size(max = 4000) String queryContext,
    @Min(1) @Max(20) Integer topK,
    @Min(0) @Max(1) Double similarityThreshold
) {
}
