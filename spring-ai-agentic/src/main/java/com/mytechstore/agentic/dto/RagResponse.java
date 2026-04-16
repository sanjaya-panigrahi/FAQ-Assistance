package com.mytechstore.agentic.dto;

public record RagResponse(String answer, String strategy, int chunksUsed) {
}
