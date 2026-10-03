package com.nexusapi.server.modules.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;

public record ApiKeyStatusRequest(
        @NotBlank String status,
        @PositiveOrZero long version
) {
}
