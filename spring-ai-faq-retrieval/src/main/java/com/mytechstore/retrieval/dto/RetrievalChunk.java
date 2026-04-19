package com.mytechstore.retrieval.dto;

public record RetrievalChunk(
    int rank,
    String content,
    String source,
    Integer chunkNumber,
    double vectorScore,
    double lexicalScore,
    double rerankScore
) {
}
