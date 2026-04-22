package com.mytechstore.graphrag.dto;

import java.time.Instant;

public record TaskResponse(
    String taskId,
    String taskType,
    String status,
    String error,
    Instant createdAt,
    Instant updatedAt
) {
}
