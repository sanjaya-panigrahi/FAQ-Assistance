package com.mytechstore.vision.dto;

import java.util.List;
import java.io.Serializable;

public record VisionRagResponse(
	String answer,
	int chunksUsed,
	String strategy,
	String orchestrationStrategy,
	String consistencyLabel,
	Double consistencyScore,
	List<String> consistencyReasons) implements Serializable {
    private static final long serialVersionUID = 1L;
}