package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenRefreshResponse(
    @JsonProperty("token")
    String token,

    @JsonProperty("expiresIn")
    long expiresIn
) {}
