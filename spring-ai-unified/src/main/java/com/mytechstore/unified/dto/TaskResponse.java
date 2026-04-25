package com.mytechstore.unified.dto;

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
