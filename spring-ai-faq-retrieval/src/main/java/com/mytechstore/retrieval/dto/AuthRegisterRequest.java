package com.mytechstore.retrieval.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AuthRegisterRequest(
    @JsonProperty("username")
    String username,

    @JsonProperty("password")
    String password,

    @JsonProperty("tenantId")
    String tenantId,

    @JsonProperty("email")
    String email
) {}
