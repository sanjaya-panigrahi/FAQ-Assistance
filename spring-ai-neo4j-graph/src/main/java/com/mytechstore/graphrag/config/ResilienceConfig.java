package com.mytechstore.graphrag.config;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

/**
 * Resilience configuration for circuit breaker and async HTTP patterns.
 * Provides circuit breakers for external services (Chroma, Neo4j, OpenAI).
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public WebClient webClient() {
        return WebClient.builder()
                .build();
    }

    /**
     * Circuit breaker for Chroma DB queries.
     * Fails after 5 consecutive errors, waits 30s before trying again.
     */
    @Bean
    public CircuitBreaker chromaCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .slowCallRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .recordExceptions(Exception.class)
                .build();
        return CircuitBreaker.of("chromaCircuitBreaker", config);
    }

    /**
     * Circuit breaker for Neo4j queries.
     */
    @Bean
    public CircuitBreaker neo4jCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(30))
                .permittedNumberOfCallsInHalfOpenState(3)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .slowCallRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(5))
                .recordExceptions(Exception.class)
                .build();
        return CircuitBreaker.of("neo4jCircuitBreaker", config);
    }

    /**
     * Circuit breaker for OpenAI API calls.
     */
    @Bean
    public CircuitBreaker openaiCircuitBreaker() {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(60))  // Longer wait for LLM
                .permittedNumberOfCallsInHalfOpenState(2)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                .slowCallRateThreshold(50.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(10))
                .recordExceptions(Exception.class)
                .build();
        return CircuitBreaker.of("openaiCircuitBreaker", config);
    }
}
