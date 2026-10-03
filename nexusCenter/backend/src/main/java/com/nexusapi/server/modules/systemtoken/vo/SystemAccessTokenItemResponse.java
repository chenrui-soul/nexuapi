package com.nexusapi.server.modules.systemtoken.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 系统访问令牌列表项，永远不包含完整 Secret 或摘要。 */
public record SystemAccessTokenItemResponse(
        UUID id,
        String name,
        @JsonProperty("masked_token") String maskedToken,
        List<String> scopes,
        @JsonProperty("ip_allowlist") List<String> ipAllowlist,
        String status,
        @JsonProperty("expires_at") Instant expiresAt,
        @JsonProperty("last_used_at") Instant lastUsedAt,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
