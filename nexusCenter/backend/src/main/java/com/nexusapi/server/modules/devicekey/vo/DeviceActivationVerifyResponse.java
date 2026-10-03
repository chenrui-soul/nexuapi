package com.nexusapi.server.modules.devicekey.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record DeviceActivationVerifyResponse(
        boolean valid,
        String status,
        @JsonProperty("application_code") String applicationCode,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("activated_at") Instant activatedAt) {}
