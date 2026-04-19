package com.mytechstore.guardrails.dto;

public record RagResponse(String answer, boolean blocked, String reason, int chunksUsed, String strategy,
	String orchestrationStrategy) {
}