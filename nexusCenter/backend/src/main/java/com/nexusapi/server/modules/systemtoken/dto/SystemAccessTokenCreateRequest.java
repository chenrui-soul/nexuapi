package com.nexusapi.server.modules.systemtoken.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/** 创建系统访问令牌的完整配置。 */
public record SystemAccessTokenCreateRequest(
        @NotBlank @Size(max = 120) String name,
        @NotEmpty @Size(max = 8) List<@NotBlank @Size(max = 64) String> scopes,
        @JsonProperty("ip_allowlist") @Size(max = 20) List<@NotBlank @Size(max = 80) String> ipAllowlist,
        @JsonProperty("expires_at") Instant expiresAt
) {
    public SystemAccessTokenCreateRequest {
        ipAllowlist = ipAllowlist == null ? List.of() : List.copyOf(ipAllowlist);
    }
}
