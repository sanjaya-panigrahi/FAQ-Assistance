package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.io.Serializable;

public record RetrievalQueryRequest(
    @JsonProperty("tenantId")
    @JsonAlias({"customerId", "customer_id", "tenant_id"})
    @Size(max = 100) String tenantId,
    @NotBlank @Size(max = 1000) String question,
    @Size(max = 4000) String queryContext,
    @Min(1) @Max(20) Integer topK,
    @Min(0) @Max(1) Double similarityThreshold
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
