package com.nexusapi.server.modules.devicekey.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

public record DeviceActivationKeyItemResponse(
        UUID id,
        String name,
        @JsonProperty("application_code") String applicationCode,
        String key,
        String secret,
        String status,
        boolean bound,
        @JsonProperty("activated_at") Instant activatedAt,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("last_verified_at") Instant lastVerifiedAt,
        @JsonProperty("created_at") Instant createdAt,
        long version) {}
