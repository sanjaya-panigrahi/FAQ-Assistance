package com.mytechstore.hier.dto;

import java.io.Serializable;

public record RagResponse(String answer, String selectedSection, int chunksUsed, String strategy,
	String orchestrationStrategy) implements Serializable {
    private static final long serialVersionUID = 1L;
}