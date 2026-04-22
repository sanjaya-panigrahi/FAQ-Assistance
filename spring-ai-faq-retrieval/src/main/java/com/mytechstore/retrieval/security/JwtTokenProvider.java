package com.mytechstore.retrieval.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Key;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT Token Provider for token generation, validation, and parsing
 * Uses JJWT library for secure token handling
 */
@Component
public class JwtTokenProvider {

    private static final Logger logger = LoggerFactory.getLogger(JwtTokenProvider.class);

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:900000}")
    private long jwtExpiration;

    @Value("${jwt.refresh-expiration:86400000}")
    private long jwtRefreshExpiration;

    @PostConstruct
    void validateConfiguration() {
        if (jwtSecret == null || jwtSecret.isBlank() || jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT secret must be configured with at least 32 characters");
        }
        if (jwtExpiration <= 0 || jwtRefreshExpiration <= 0) {
            throw new IllegalStateException("JWT expiration values must be positive");
        }
        if (jwtRefreshExpiration < jwtExpiration) {
            throw new IllegalStateException("Refresh token expiration must be greater than or equal to access token expiration");
        }
    }

    /**
     * Generate JWT token for authenticated user
     */
    public String generateToken(String username, String tenantId, String role) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("tenantId", tenantId);
        claims.put("role", role);
        
        return createToken(claims, username, jwtExpiration);
    }

    /**
     * Generate refresh token with longer expiration
     */
    public String generateRefreshToken(String username) {
        return createToken(new HashMap<>(), username, jwtRefreshExpiration);
    }

    /**
     * Create JWT token with claims and expiration
     */
    private String createToken(Map<String, Object> claims, String subject, long expiration) {
        long now = System.currentTimeMillis();
        Date issuedAt = new Date(now);
        Date expiresAt = new Date(now + expiration);

        SecretKey key = getSigningKey();

        return Jwts.builder()
            .setClaims(claims)
            .setSubject(subject)
            .setIssuedAt(issuedAt)
            .setExpiration(expiresAt)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact();
    }

    /**
     * Extract username from JWT token
     */
    public String getUsernameFromToken(String token) {
        try {
            Claims claims = parseClaims(token);
            
            return claims.getSubject();
        } catch (JwtException e) {
            logger.warn("Failed to extract username from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract tenant ID from JWT token
     */
    public String getTenantIdFromToken(String token) {
        try {
            Claims claims = parseClaims(token);
            
            return (String) claims.get("tenantId");
        } catch (JwtException e) {
            logger.warn("Failed to extract tenantId from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract role from JWT token
     */
    public String getRoleFromToken(String token) {
        try {
            Claims claims = parseClaims(token);
            
            return (String) claims.get("role");
        } catch (JwtException e) {
            logger.warn("Failed to extract role from token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Validate JWT token
     */
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            
            return true;
        } catch (SecurityException e) {
            logger.error("Invalid JWT signature: {}", e.getMessage());
        } catch (MalformedJwtException e) {
            logger.error("Invalid JWT token: {}", e.getMessage());
        } catch (ExpiredJwtException e) {
            logger.error("Expired JWT token: {}", e.getMessage());
        } catch (UnsupportedJwtException e) {
            logger.error("Unsupported JWT token: {}", e.getMessage());
        } catch (IllegalArgumentException e) {
            logger.error("JWT claims string is empty: {}", e.getMessage());
        }
        
        return false;
    }

    /**
     * Check if token is expired
     */
    public boolean isTokenExpired(String token) {
        try {
            Claims claims = parseClaims(token);
            
            return claims.getExpiration().before(new Date());
        } catch (ExpiredJwtException e) {
            return true;
        } catch (JwtException e) {
            logger.warn("Error checking token expiration: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Get expiration time from token
     */
    public Date getTokenExpiration(String token) {
        try {
            Claims claims = parseClaims(token);
            
            return claims.getExpiration();
        } catch (JwtException e) {
            logger.warn("Error extracting expiration: {}", e.getMessage());
            return null;
        }
    }

    private SecretKey getSigningKey() {
        return Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
            .verifyWith(getSigningKey())
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }
}
