package com.mytechstore.graphrag.dto;

import java.io.Serializable;

public record RagResponse(String answer, int vectorChunks, int graphFacts, String strategy,
	String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}