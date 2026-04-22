package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthRegisterResponse(
    @JsonProperty("id")
    String id,

    @JsonProperty("username")
    String username,

    @JsonProperty("tenantId")
    String tenantId,

    @JsonProperty("email")
    String email,

    @JsonProperty("message")
    String message
) {}
