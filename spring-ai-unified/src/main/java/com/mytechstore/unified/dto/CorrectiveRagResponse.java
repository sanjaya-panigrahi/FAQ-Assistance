package com.mytechstore.unified.dto;

import java.io.Serializable;

public record CorrectiveRagResponse(String answer, boolean blocked, String reason, int chunksUsed, String strategy,
        String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}
