package com.mytechstore.graphrag.dto;

public record RagResponse(String answer, int vectorChunks, int graphFacts) {
}