package com.mytechstore.unified.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.events.consumer-enabled", havingValue = "true")
public class GraphTaskEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(GraphTaskEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final GraphTaskEventStats stats;
    private final MeterRegistry meterRegistry;

    public GraphTaskEventConsumer(ObjectMapper objectMapper, GraphTaskEventStats stats, MeterRegistry meterRegistry) {
        this.objectMapper = objectMapper;
        this.stats = stats;
        this.meterRegistry = meterRegistry;
    }

    @KafkaListener(
        topics = "${app.events.topic}",
        groupId = "${app.events.consumer-group-id:spring-ai-unified-graph-consumer}"
    )
    public void consume(String payload) {
        try {
            GraphTaskEvent event = objectMapper.readValue(payload, GraphTaskEvent.class);
            String eventType = event.eventType();
            String status = event.status() == null || event.status().isBlank() ? "UNKNOWN" : event.status();

            stats.markConsumed(eventType);
            meterRegistry.counter(
                "graph.task.events.consumed",
                "eventType", eventType == null || eventType.isBlank() ? "UNKNOWN" : eventType,
                "status", status
            ).increment();
        } catch (Exception ex) {
            stats.markParseError();
            meterRegistry.counter("graph.task.events.parse.errors").increment();
            log.warn("Failed to parse consumed graph task event payload", ex);
        }
    }
}
