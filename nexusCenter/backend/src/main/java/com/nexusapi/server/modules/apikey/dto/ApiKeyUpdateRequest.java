package com.nexusapi.server.modules.apikey.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApiKeyUpdateRequest(
        @NotBlank @Size(max = 100) String name,
        @JsonProperty("service_group_id") UUID serviceGroupId,
        /** 旧版默认分组字段，仅用于兼容尚未升级的客户端。 */
        @JsonProperty("default_group_id") UUID defaultGroupId,
        @NotNull @Size(max = 100) @JsonProperty("allowed_model_ids") List<UUID> allowedModelIds,
        @NotNull @Size(max = 50) @JsonProperty("allowed_group_ids") List<UUID> allowedGroupIds,
        @NotNull @Size(max = 50) @JsonProperty("ip_allowlist") List<@NotBlank @Size(max = 64) String> ipAllowlist,
        @Positive @Max(100_000) @JsonProperty("rpm_limit") Integer rpmLimit,
        @Positive @Max(1_000_000_000_000L) @JsonProperty("tpm_limit") Long tpmLimit,
        @Positive @Max(10_000) @JsonProperty("concurrency_limit") Integer concurrencyLimit,
        @DecimalMin("0.00000001") @Digits(integer = 12, fraction = 8) @JsonProperty("credit_limit") BigDecimal creditLimit,
        @Future @JsonProperty("expires_at") Instant expiresAt,
        @PositiveOrZero long version
) {
}
