package com.mytechstore.agentic.dto;

import java.io.Serializable;

public record RagResponse(String answer, String strategy, int chunksUsed, String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}
