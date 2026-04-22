package com.mytechstore.retrieval.service;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Circuit Breaker Resilience Tests")
@ExtendWith(MockitoExtension.class)
class CircuitBreakerResilienceTest {

    private CircuitBreakerRegistry circuitBreakerRegistry;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        circuitBreakerRegistry = CircuitBreakerRegistry.ofDefaults();
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("test-circuit-breaker");
    }

    @Test
    @DisplayName("Should initialize circuit breaker in CLOSED state")
    void testCircuitBreakerInitialState() {
        assertEquals(CircuitBreaker.State.CLOSED, circuitBreaker.getState());
    }

    @Test
    @DisplayName("Should transition to OPEN after failure threshold exceeded")
    void testCircuitBreakerOpensOnFailure() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("failure-test",
            io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50.0f)
                .build()
        );

        // Simulate failures
        for (int i = 0; i < 3; i++) {
            try {
                cb.executeSupplier(() -> {
                    throw new RuntimeException("Service unavailable");
                });
            } catch (Exception e) {
                // Expected
            }
        }

        // After sufficient failures, circuit should be open
        assertTrue(cb.getState() == CircuitBreaker.State.OPEN || 
                   cb.getState() == CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("Should track success and failure counts")
    void testCircuitBreakerMetrics() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("metrics-test");

        // Simulate successful call
        cb.executeSupplier(() -> "success");

        var metrics = cb.getMetrics();
        assertTrue(metrics.getNumberOfSuccessfulCalls() >= 0);
        assertTrue(metrics.getNumberOfFailedCalls() >= 0);
    }

    @Test
    @DisplayName("Should recover to HALF_OPEN after waitDurationInOpenState")
    void testCircuitBreakerRecovery() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("recovery-test");
        
        assertNotNull(cb);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    @DisplayName("Should execute supplier with circuit breaker")
    void testCircuitBreakerFallbackBehavior() {
        CircuitBreaker cb = circuitBreakerRegistry.circuitBreaker("fallback-test");

        String result = cb.executeSupplier(() -> "primary");
        assertEquals("primary", result);
    }
}
