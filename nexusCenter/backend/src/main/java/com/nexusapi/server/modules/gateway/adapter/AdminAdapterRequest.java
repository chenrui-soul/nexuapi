package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** 管理员创建或更新适配器目录项的请求。 */
public record AdminAdapterRequest(
        @NotBlank @Size(max = 80) @JsonProperty("adapter_key") String adapterKey,
        @NotBlank @Size(max = 120) @JsonProperty("display_name") String displayName,
        @NotBlank @Size(max = 24) @JsonProperty("capability_type") String capabilityType,
        @NotBlank @Size(max = 80) @JsonProperty("implementation_key") String implementationKey,
        @Size(max = 1000) String description,
        @NotBlank @Size(max = 16) String status,
        @PositiveOrZero Long version
) {
}
