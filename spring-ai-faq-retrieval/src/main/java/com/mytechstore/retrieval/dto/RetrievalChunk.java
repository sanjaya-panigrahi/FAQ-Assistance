package com.mytechstore.retrieval.dto;

import java.io.Serializable;

public record RetrievalChunk(
    int rank,
    String content,
    String source,
    Integer chunkNumber,
    double vectorScore,
    double lexicalScore,
    double rerankScore
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
