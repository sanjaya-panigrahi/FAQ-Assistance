package com.mytechstore.retrieval.dto;

import java.io.Serializable;
import java.util.List;

public record RetrievalQueryResponse(
    String tenantId,
    String question,
    String transformedQuery,
    String strategy,
    String answer,
    int chunksUsed,
    boolean grounded,
    int retrievalLatencyMs,
    int generationLatencyMs,
    List<RetrievalChunk> chunks
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
