package com.mytechstore.graphrag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.graphrag.dto.TaskResponse;
import com.mytechstore.graphrag.event.GraphTaskEventPublisher;
import com.mytechstore.graphrag.event.GraphTaskEventTypes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class TaskService {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String redisPrefix;
    private final String redisIndexKey;
    private final Duration taskTtl;
    private final GraphTaskEventPublisher eventPublisher;

    public TaskService(
        StringRedisTemplate redisTemplate,
        ObjectMapper objectMapper,
        GraphTaskEventPublisher eventPublisher,
        @Value("${app.tasks.redis-prefix}") String redisPrefix,
        @Value("${app.tasks.ttl}") Duration taskTtl
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
        this.redisPrefix = redisPrefix;
        this.redisIndexKey = redisPrefix + "_index";
        this.taskTtl = taskTtl;
    }

    public TaskResponse createTask(String taskType) {
        Instant now = Instant.now();
        TaskResponse task = new TaskResponse(
            UUID.randomUUID().toString(),
            taskType,
            "PENDING",
            null,
            now,
            now
        );
        save(task);
        registerTask(task.taskId());
        eventPublisher.publish(GraphTaskEventTypes.TASK_CREATED, task);
        return task;
    }

    public TaskResponse markRunning(String taskId) {
        return update(taskId, "RUNNING", null);
    }

    public TaskResponse markComplete(String taskId) {
        return update(taskId, "COMPLETE", null);
    }

    public TaskResponse markFailed(String taskId, String error) {
        String safeError = error == null ? "Task failed" : error;
        return update(taskId, "FAILED", safeError);
    }

    public TaskResponse getTask(String taskId) {
        return load(taskId);
    }

    public List<TaskResponse> listTasks(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        List<String> taskIds = redisTemplate.opsForList().range(redisIndexKey, 0, safeLimit - 1);
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }

        List<TaskResponse> tasks = new ArrayList<>();
        for (String taskId : taskIds) {
            try {
                tasks.add(load(taskId));
            } catch (ResponseStatusException ignored) {
                // Task may have expired by TTL while index still contains its id.
            }
        }
        return tasks;
    }

    private TaskResponse update(String taskId, String status, String error) {
        TaskResponse existing = load(taskId);
        TaskResponse updated = new TaskResponse(
            existing.taskId(),
            existing.taskType(),
            status,
            error,
            existing.createdAt(),
            Instant.now()
        );
        save(updated);
        eventPublisher.publish(resolveEventType(status), updated);
        return updated;
    }

    private String resolveEventType(String status) {
        if ("RUNNING".equals(status)) {
            return GraphTaskEventTypes.TASK_RUNNING;
        }
        if ("COMPLETE".equals(status)) {
            return GraphTaskEventTypes.TASK_COMPLETED;
        }
        if ("FAILED".equals(status)) {
            return GraphTaskEventTypes.TASK_FAILED;
        }
        return GraphTaskEventTypes.TASK_CREATED;
    }

    private void save(TaskResponse task) {
        try {
            redisTemplate.opsForValue().set(redisKey(task.taskId()), objectMapper.writeValueAsString(task), taskTtl);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize task " + task.taskId(), ex);
        }
    }

    private void registerTask(String taskId) {
        redisTemplate.opsForList().leftPush(redisIndexKey, taskId);
        redisTemplate.opsForList().trim(redisIndexKey, 0, 199);
        redisTemplate.expire(redisIndexKey, taskTtl);
    }

    private TaskResponse load(String taskId) {
        String payload = redisTemplate.opsForValue().get(redisKey(taskId));
        if (payload == null || payload.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, "Task not found: " + taskId);
        }
        try {
            return objectMapper.readValue(payload, TaskResponse.class);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to deserialize task " + taskId, ex);
        }
    }

    private String redisKey(String taskId) {
        return redisPrefix + taskId;
    }
}
