package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthLoginResponse(
    @JsonProperty("token")
    String token,

    @JsonProperty("refreshToken")
    String refreshToken,

    @JsonProperty("username")
    String username,

    @JsonProperty("tenantId")
    String tenantId,

    @JsonProperty("role")
    String role,

    @JsonProperty("expiresIn")
    long expiresIn
) {}
