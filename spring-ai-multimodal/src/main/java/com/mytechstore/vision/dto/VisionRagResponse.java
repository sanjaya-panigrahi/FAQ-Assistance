package com.mytechstore.vision.dto;

import java.util.List;

public record VisionRagResponse(
	String answer,
	int chunksUsed,
	String strategy,
	String orchestrationStrategy,
	String consistencyLabel,
	Double consistencyScore,
	List<String> consistencyReasons) {
}