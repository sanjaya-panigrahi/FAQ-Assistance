package com.mytechstore.guardrails.dto;

import java.io.Serializable;

public record RagResponse(String answer, boolean blocked, String reason, int chunksUsed, String strategy,
	String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}