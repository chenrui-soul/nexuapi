package com.nexusapi.server.modules.apikey.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyCreatedResponse(
        UUID id,
        String name,
        String secret,
        @JsonProperty("masked_key") String maskedKey,
        String status,
        @JsonProperty("created_at") Instant createdAt,
        long version
) {
}
