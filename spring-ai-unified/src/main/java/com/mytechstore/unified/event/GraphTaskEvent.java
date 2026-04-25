package com.mytechstore.unified.event;

import java.time.Instant;

public record GraphTaskEvent(
    String eventId,
    String eventType,
    String sourceService,
    String taskId,
    String taskType,
    String status,
    String error,
    Instant occurredAt
) {
}
