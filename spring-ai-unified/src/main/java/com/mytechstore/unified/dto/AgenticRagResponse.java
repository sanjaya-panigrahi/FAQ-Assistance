package com.mytechstore.unified.dto;

import java.io.Serializable;

public record AgenticRagResponse(String answer, String strategy, int chunksUsed, String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}
