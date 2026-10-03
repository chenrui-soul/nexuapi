package com.nexusapi.server.modules.devicekey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record DeviceActivationKeyCreateRequest(
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 120) @JsonProperty("application_code") String applicationCode,
        @JsonProperty("expires_at") Instant expiresAt
) {}
