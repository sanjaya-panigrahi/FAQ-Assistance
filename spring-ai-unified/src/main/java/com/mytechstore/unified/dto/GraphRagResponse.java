package com.mytechstore.unified.dto;

import java.io.Serializable;

public record GraphRagResponse(String answer, int vectorChunks, int graphFacts, String strategy,
        String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}
