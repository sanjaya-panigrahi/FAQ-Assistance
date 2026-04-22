package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthLoginRequest(
    @JsonProperty("username")
    String username,

    @JsonProperty("password")
    String password,

    @JsonProperty("tenantId")
    String tenantId
) {}
