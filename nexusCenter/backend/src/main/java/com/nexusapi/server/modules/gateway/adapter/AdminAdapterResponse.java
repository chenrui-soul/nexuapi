package com.nexusapi.server.modules.gateway.adapter;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 适配器目录响应；不返回任何凭证或上游连接信息。 */
public record AdminAdapterResponse(
        UUID id,
        @JsonProperty("adapter_key") String adapterKey,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("capability_type") String capabilityType,
        @JsonProperty("implementation_key") String implementationKey,
        @JsonProperty("implementation_label") String implementationLabel,
        String description,
        String status,
        @JsonProperty("built_in") boolean builtIn,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
