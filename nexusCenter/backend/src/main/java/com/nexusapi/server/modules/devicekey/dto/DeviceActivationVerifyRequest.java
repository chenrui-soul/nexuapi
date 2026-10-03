package com.nexusapi.server.modules.devicekey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DeviceActivationVerifyRequest(
        @NotBlank String secret,
        @NotBlank @Size(max = 512) @JsonProperty("device_code") String deviceCode
) {}
