package com.nexusapi.server.modules.auth.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AuthUserResponse(
        UUID id,
        String name,
        String email,
        String status,
        List<String> roles,
        @JsonProperty("created_at") Instant createdAt
) {
}
