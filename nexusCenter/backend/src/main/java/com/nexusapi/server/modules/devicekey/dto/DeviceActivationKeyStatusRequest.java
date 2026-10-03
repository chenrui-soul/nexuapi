package com.nexusapi.server.modules.devicekey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record DeviceActivationKeyStatusRequest(
        @NotBlank String status,
        @PositiveOrZero long version
) {}
