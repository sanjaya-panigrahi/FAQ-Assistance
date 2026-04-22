package com.mytechstore.retrieval.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;

/**
 * Gateway Configuration with Rate Limiting
 * - Enforces tenant-based rate limits
 * - Supports burst capacity for peak loads
 * - Integrates with Resilience4j for consistent patterns
 */
@Configuration
public class RateLimitingConfig {

    @Bean
    public RateLimiter tenantRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("tenant-limiter", io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
            .limitRefreshPeriod(java.time.Duration.ofMinutes(1))
            .limitForPeriod(100)  // 100 requests per minute
            .timeoutDuration(java.time.Duration.ofSeconds(5))
            .build());
    }

    @Bean
    public RateLimiter apiRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("api-limiter", io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
            .limitRefreshPeriod(java.time.Duration.ofMinutes(1))
            .limitForPeriod(1000)
            .timeoutDuration(java.time.Duration.ofSeconds(5))
            .build());
    }

    @Bean
    public RateLimiter authRateLimiter(RateLimiterRegistry registry) {
        return registry.rateLimiter("auth-limiter", io.github.resilience4j.ratelimiter.RateLimiterConfig.custom()
            .limitRefreshPeriod(java.time.Duration.ofMinutes(1))
            .limitForPeriod(5)
            .timeoutDuration(java.time.Duration.ZERO)
            .build());
    }

}
