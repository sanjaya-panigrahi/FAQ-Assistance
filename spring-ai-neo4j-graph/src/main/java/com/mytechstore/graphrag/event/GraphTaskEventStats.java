package com.mytechstore.graphrag.event;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class GraphTaskEventStats {

    private final AtomicLong totalConsumed = new AtomicLong(0);
    private final AtomicLong parseErrors = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> perTypeCounts = new ConcurrentHashMap<>();

    public void markConsumed(String eventType) {
        totalConsumed.incrementAndGet();
        String key = eventType == null || eventType.isBlank() ? "UNKNOWN" : eventType;
        perTypeCounts.computeIfAbsent(key, ignored -> new AtomicLong(0)).incrementAndGet();
    }

    public void markParseError() {
        parseErrors.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("totalConsumed", totalConsumed.get());
        payload.put("parseErrors", parseErrors.get());

        Map<String, Long> eventTypes = new LinkedHashMap<>();
        perTypeCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> eventTypes.put(entry.getKey(), entry.getValue().get()));
        payload.put("eventTypes", eventTypes);
        return payload;
    }
}
