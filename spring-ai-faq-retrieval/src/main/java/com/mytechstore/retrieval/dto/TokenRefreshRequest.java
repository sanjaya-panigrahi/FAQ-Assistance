package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TokenRefreshRequest(
    @JsonProperty("refreshToken")
    String refreshToken
) {}
