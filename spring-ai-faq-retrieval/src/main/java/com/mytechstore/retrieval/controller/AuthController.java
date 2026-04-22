package com.mytechstore.retrieval.controller;

import com.mytechstore.retrieval.dto.*;
import com.mytechstore.retrieval.security.JwtTokenProvider;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Authentication Controller
 * Handles user login, registration, and token refresh
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;
    private final boolean registrationEnabled;
    private final Map<String, UserCredential> userStore = new ConcurrentHashMap<>();

    public AuthController(
        JwtTokenProvider jwtTokenProvider,
        PasswordEncoder passwordEncoder,
        @Value("${auth.registration-enabled:false}") boolean registrationEnabled,
        @Value("${auth.bootstrap.username:}") String bootstrapUsername,
        @Value("${auth.bootstrap.password-hash:}") String bootstrapPasswordHash,
        @Value("${auth.bootstrap.tenant-id:}") String bootstrapTenantId,
        @Value("${auth.bootstrap.role:ADMIN}") String bootstrapRole
    ) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.passwordEncoder = passwordEncoder;
        this.registrationEnabled = registrationEnabled;

        if (!bootstrapUsername.isBlank()) {
            if (bootstrapPasswordHash.isBlank() || bootstrapTenantId.isBlank()) {
                throw new IllegalStateException("Bootstrap auth user requires username, password hash, and tenant ID");
            }
            userStore.put(bootstrapUsername, new UserCredential(
                bootstrapUsername,
                bootstrapPasswordHash,
                bootstrapTenantId,
                bootstrapRole.toUpperCase()
            ));
            logger.info("Loaded bootstrap auth user: {}", bootstrapUsername);
        }
    }

    /**
     * Login endpoint
     */
    @PostMapping("/login")
    @RateLimiter(name = "auth-limiter")
    public ResponseEntity<?> login(@RequestBody AuthLoginRequest request) {
        logger.info("Login attempt for user: {} in tenant: {}", request.username(), request.tenantId());

        if (userStore.isEmpty()) {
            logger.error("Login attempted but no authentication provider is configured");
            return ResponseEntity.status(503).body(Map.of("error", "Authentication is not configured"));
        }

        UserCredential user = userStore.get(request.username());
        if (user == null) {
            logger.warn("User not found: {}", request.username());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        if (!passwordEncoder.matches(request.password(), user.password())) {
            logger.warn("Invalid password for user: {}", request.username());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid username or password"));
        }

        if (!user.tenantId().equals(request.tenantId())) {
            logger.warn("Tenant mismatch for user: {}", request.username());
            return ResponseEntity.status(401).body(Map.of("error", "Invalid tenant"));
        }

        // Generate tokens
        String accessToken = jwtTokenProvider.generateToken(
            user.username(),
            user.tenantId(),
            user.role()
        );
        String refreshToken = jwtTokenProvider.generateRefreshToken(user.username());

        Date expiresAt = jwtTokenProvider.getTokenExpiration(accessToken);
        long expiresIn = expiresAt != null ? expiresAt.getTime() - System.currentTimeMillis() : 86400000;

        AuthLoginResponse response = new AuthLoginResponse(
            accessToken,
            refreshToken,
            user.username(),
            user.tenantId(),
            user.role(),
            expiresIn
        );

        logger.info("Login successful for user: {}", request.username());
        return ResponseEntity.ok(response);
    }

    /**
     * Register endpoint
     */
    @PostMapping("/register")
    @RateLimiter(name = "auth-limiter")
    public ResponseEntity<?> register(@RequestBody AuthRegisterRequest request) {
        if (!registrationEnabled) {
            logger.warn("Registration attempted while disabled");
            return ResponseEntity.status(403).body(Map.of("error", "Self-service registration is disabled"));
        }

        logger.info("Registration attempt for user: {} in tenant: {}", request.username(), request.tenantId());

        if (userStore.containsKey(request.username())) {
            logger.warn("User already exists: {}", request.username());
            return ResponseEntity.status(400).body(Map.of("error", "Username already exists"));
        }

        // Hash password
        String hashedPassword = passwordEncoder.encode(request.password());

        // Create new user
        UserCredential newUser = new UserCredential(
            request.username(),
            hashedPassword,
            request.tenantId(),
            "USER"
        );

        userStore.put(request.username(), newUser);

        AuthRegisterResponse response = new AuthRegisterResponse(
            request.username(),
            request.username(),
            request.tenantId(),
            request.email(),
            "User registered successfully"
        );

        logger.info("Registration successful for user: {}", request.username());
        return ResponseEntity.status(201).body(response);
    }

    /**
     * Refresh token endpoint
     */
    @PostMapping("/refresh")
    @RateLimiter(name = "auth-limiter")
    public ResponseEntity<?> refreshToken(@RequestBody TokenRefreshRequest request) {
        if (!jwtTokenProvider.validateToken(request.refreshToken())) {
            logger.warn("Invalid refresh token");
            return ResponseEntity.status(401).body(Map.of("error", "Invalid refresh token"));
        }

        String username = jwtTokenProvider.getUsernameFromToken(request.refreshToken());
        UserCredential user = userStore.get(username);

        if (user == null) {
            logger.warn("User not found for refresh: {}", username);
            return ResponseEntity.status(401).body(Map.of("error", "User not found"));
        }

        // Generate new access token
        String newAccessToken = jwtTokenProvider.generateToken(
            user.username(),
            user.tenantId(),
            user.role()
        );

        Date expiresAt = jwtTokenProvider.getTokenExpiration(newAccessToken);
        long expiresIn = expiresAt != null ? expiresAt.getTime() - System.currentTimeMillis() : 86400000;

        TokenRefreshResponse response = new TokenRefreshResponse(
            newAccessToken,
            expiresIn
        );

        logger.info("Token refreshed for user: {}", username);
        return ResponseEntity.ok(response);
    }

    /**
     * User credential holder
     */
    private static class UserCredential {
        private final String username;
        private final String password;
        private final String tenantId;
        private final String role;

        public UserCredential(String username, String password, String tenantId, String role) {
            this.username = username;
            this.password = password;
            this.tenantId = tenantId;
            this.role = role;
        }

        public String username() { return username; }
        public String password() { return password; }
        public String tenantId() { return tenantId; }
        public String role() { return role; }
    }
}
