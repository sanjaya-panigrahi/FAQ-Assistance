package com.mytechstore.unified.service;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class OpenAiVisionExtractor {

    public record ConsistencyResult(String label, Double score, List<String> reasons) {
    }

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public OpenAiVisionExtractor(
            ObjectMapper objectMapper,
            @Value("${spring.ai.openai.api-key:}") String apiKey,
            @Value("${openai.vision.model:gpt-4o-mini}") String model,
            WebClient webClient) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.model = model;
    }

    public String extractImageSignals(byte[] imageBytes, String contentType) {
        if (imageBytes == null || imageBytes.length == 0) {
            return "";
        }
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }

        try {
            String imageB64 = Base64.getEncoder().encodeToString(imageBytes);
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of(
                                    "role", "user",
                                    "content", List.of(
                                            Map.of(
                                                    "type", "text",
                                                    "text", "Extract concise visual signals from this support image for RAG use. "
                                                            + "Return one short paragraph covering product type, visible condition, damage signs, "
                                                            + "packaging state, and anything relevant to return/warranty policy."),
                                            Map.of(
                                                    "type", "image_url",
                                                    "image_url", Map.of(
                                                            "url", "data:" + contentType + ";base64," + imageB64))))),
                    "temperature", 0);

            String responseBody = webClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
            if (responseBody == null || responseBody.isBlank()) {
                return "";
            }

            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            return contentNode.isMissingNode() ? "" : contentNode.asText("").trim();
        } catch (Exception ex) {
            return "";
        }
    }

    public ConsistencyResult evaluateConsistency(String userNotes, String visualSignals) {
        if (userNotes == null || userNotes.isBlank() || visualSignals == null || visualSignals.isBlank()) {
            return new ConsistencyResult(null, null, List.of());
        }
        if (apiKey == null || apiKey.isBlank()) {
            return new ConsistencyResult(null, null, List.of());
        }

        try {
            String textPrompt = "You compare user image notes with extracted visual signals. "
                    + "Return ONLY valid JSON with keys: label, score, reasons. "
                    + "label must be one of: match, partial, mismatch. "
                    + "score must be between 0 and 1. "
                    + "reasons must be an array of short strings.\n\n"
                    + "User notes: " + userNotes + "\n"
                    + "Extracted visual signals: " + visualSignals;

            Map<String, Object> payload = Map.of(
                    "model", model,
                    "messages", List.of(Map.of("role", "user", "content", textPrompt)),
                    "temperature", 0);

                String responseBody = webClient.post()
                    .uri("https://api.openai.com/v1/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(30));
                if (responseBody == null || responseBody.isBlank()) {
                return new ConsistencyResult(null, null, List.of());
            }

                JsonNode root = objectMapper.readTree(responseBody);
            String rawContent = root.path("choices").path(0).path("message").path("content").asText("").trim();
            if (rawContent.isBlank()) {
                return new ConsistencyResult(null, null, List.of());
            }

            if (rawContent.startsWith("```")) {
                rawContent = rawContent.replace("```json", "").replace("```", "").trim();
            }

            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(rawContent);
            } catch (IOException ex) {
                return new ConsistencyResult(null, null, List.of());
            }

            String label = parsed.path("label").asText("").trim();
            if (!Set.of("match", "partial", "mismatch").contains(label)) {
                label = null;
            }

            Double score = null;
            JsonNode scoreNode = parsed.path("score");
            if (scoreNode.isNumber()) {
                score = Math.max(0.0, Math.min(1.0, scoreNode.asDouble()));
            }

            List<String> reasons = new java.util.ArrayList<>();
            JsonNode reasonsNode = parsed.path("reasons");
            if (reasonsNode.isArray()) {
                for (JsonNode node : reasonsNode) {
                    reasons.add(node.asText(""));
                }
            }
            return new ConsistencyResult(label, score, reasons);
        } catch (Exception ex) {
            return new ConsistencyResult(null, null, List.of());
        }
    }
}
