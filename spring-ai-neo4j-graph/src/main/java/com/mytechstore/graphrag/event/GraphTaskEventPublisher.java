package com.mytechstore.graphrag.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mytechstore.graphrag.dto.TaskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class GraphTaskEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(GraphTaskEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final String topic;
    private final String sourceService;

    public GraphTaskEventPublisher(
        KafkaTemplate<String, String> kafkaTemplate,
        ObjectMapper objectMapper,
        @Value("${app.events.enabled:false}") boolean enabled,
        @Value("${app.events.topic:graph.tasks.lifecycle.v1}") String topic,
        @Value("${app.events.source-service:spring-ai-neo4j-graph}") String sourceService
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.topic = topic;
        this.sourceService = sourceService;
    }

    public void publish(String eventType, TaskResponse task) {
        if (!enabled || task == null) {
            return;
        }

        GraphTaskEvent event = new GraphTaskEvent(
            UUID.randomUUID().toString(),
            eventType,
            sourceService,
            task.taskId(),
            task.taskType(),
            task.status(),
            task.error(),
            Instant.now()
        );

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, task.taskId(), payload).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish graph task event {} for task {}", eventType, task.taskId(), ex);
                }
            });
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize graph task event {} for task {}", eventType, task.taskId(), ex);
        }
    }
}
