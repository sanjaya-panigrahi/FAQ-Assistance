package com.mytechstore.hier.dto;

public record RagResponse(String answer, String selectedSection, int chunksUsed, String strategy,
	String orchestrationStrategy) {
}