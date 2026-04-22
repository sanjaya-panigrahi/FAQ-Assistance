package com.mytechstore.retrieval.service;

import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Lightweight semantic intent matcher using embedding centroids with lexical fallback.
 */
class SemanticIntentMatcher {

    static final class IntentMatch {
        private final String name;
        private final double score;

        IntentMatch(String name, double score) {
            this.name = name;
            this.score = score;
        }

        String name() {
            return name;
        }

        double score() {
            return score;
        }
    }

    private static final double MIN_CONFIDENCE = 0.20;

    private final EmbeddingModel embeddingModel;
    private final Map<String, List<String>> prototypes;
    private final Map<String, float[]> prototypeCentroids = new ConcurrentHashMap<>();

    SemanticIntentMatcher(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        this.prototypes = buildPrototypes();
    }

    IntentMatch match(String question) {
        String q = question == null ? "" : question.trim();
        if (q.isBlank()) {
            return new IntentMatch("general", 0.0);
        }

        try {
            float[] qVec = embeddingModel.embed(q);
            if (qVec == null || qVec.length == 0) {
                return lexicalFallback(q);
            }

            String bestIntent = "general";
            double bestScore = -1.0;
            for (String intent : prototypes.keySet()) {
                float[] centroid = prototypeCentroids.computeIfAbsent(intent, this::computeCentroid);
                if (centroid == null || centroid.length == 0) {
                    continue;
                }
                double sim = cosine(qVec, centroid);
                if (sim > bestScore) {
                    bestScore = sim;
                    bestIntent = intent;
                }
            }

            if (bestScore < MIN_CONFIDENCE) {
                return lexicalFallback(q);
            }
            return new IntentMatch(bestIntent, bestScore);
        } catch (Exception ignored) {
            return lexicalFallback(q);
        }
    }

    private float[] computeCentroid(String intent) {
        List<String> examples = prototypes.get(intent);
        if (examples == null || examples.isEmpty()) {
            return new float[0];
        }

        List<float[]> vectors = new ArrayList<>();
        for (String sample : examples) {
            try {
                float[] vec = embeddingModel.embed(sample);
                if (vec != null && vec.length > 0) {
                    vectors.add(vec);
                }
            } catch (Exception ignored) {
                // Continue with available vectors.
            }
        }
        if (vectors.isEmpty()) {
            return new float[0];
        }

        int dimension = vectors.get(0).length;
        float[] mean = new float[dimension];
        for (float[] vec : vectors) {
            int limit = Math.min(dimension, vec.length);
            for (int i = 0; i < limit; i++) {
                mean[i] += vec[i];
            }
        }
        for (int i = 0; i < dimension; i++) {
            mean[i] = mean[i] / vectors.size();
        }
        return mean;
    }

    private IntentMatch lexicalFallback(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        if ((q.contains("product") || q.contains("products"))
            && (q.contains("new") || q.contains("refurb") || q.contains("used") || q.contains("pre-owned"))) {
            return new IntentMatch("product_availability", 0.0);
        }
        if (q.contains("return") || q.contains("refund") || q.contains("warranty") || q.contains("replace")) {
            return new IntentMatch("policy", 0.0);
        }
        if (q.contains("shipping") || q.contains("delivery") || q.contains("track") || q.contains("dispatch")) {
            return new IntentMatch("logistics", 0.0);
        }
        return new IntentMatch("general", 0.0);
    }

    private Map<String, List<String>> buildPrototypes() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put(
            "product_availability",
            List.of(
                "do you sell new product or refurbished product",
                "are refurbished products available",
                "new and refurbished product availability",
                "do you have certified refurbished devices"
            )
        );
        map.put(
            "policy",
            List.of(
                "what is your return policy",
                "refund and replacement rules",
                "warranty coverage details",
                "policy for defective items"
            )
        );
        map.put(
            "logistics",
            List.of(
                "shipping and delivery timeline",
                "how long does delivery take",
                "track my shipment",
                "dispatch and delivery options"
            )
        );
        map.put(
            "general",
            List.of(
                "general question about store",
                "help me with faq",
                "information about services",
                "customer support question"
            )
        );
        return map;
    }

    private double cosine(float[] a, float[] b) {
        int limit = Math.min(a.length, b.length);
        if (limit == 0) {
            return 0.0;
        }

        double dot = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        for (int i = 0; i < limit; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0.0 || normB <= 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
