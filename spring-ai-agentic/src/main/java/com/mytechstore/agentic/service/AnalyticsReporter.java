package com.mytechstore.agentic.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AnalyticsReporter {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsReporter.class);
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final String analyticsUrl;

    public AnalyticsReporter(
            @Value("${analytics.url:http://rag-analytics:9191}") String analyticsUrl) {
        this.analyticsUrl = analyticsUrl;
    }

    public void postEvent(String question, String responseText, String customerId,
                          String ragPattern, String strategy, long latencyMs,
                          String contextDocs) {
        String payload = """
                {"events":[{"requestId":"%s","query":"%s","response":"%s","customer":"%s",\
                "ragPattern":"%s","framework":"spring-ai","strategy":"%s","status":"success",\
                "latencyMs":%d,"contextDocs":"%s"}]}"""
                .formatted(
                        UUID.randomUUID(),
                        escapeJson(question),
                        escapeJson(truncate(responseText, 2000)),
                        escapeJson(customerId),
                        escapeJson(ragPattern),
                        escapeJson(strategy),
                        latencyMs,
                        escapeJson(truncate(contextDocs, 8000)));

        CompletableFuture.runAsync(() -> {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(analyticsUrl + "/api/analytics/events"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload))
                        .timeout(Duration.ofSeconds(5))
                        .build();
                httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                log.debug("Analytics posting failed: {}", e.getMessage());
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }
}
