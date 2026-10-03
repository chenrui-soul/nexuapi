package com.nexusapi.server.modules.channel.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** 供应商上游接口配置；credential 仅为旧客户端兼容字段，新密钥必须在服务分组中配置。 */
public record AdminChannelRequest(
        @NotNull @JsonProperty("supplier_id") UUID supplierId,
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 80) @JsonProperty("provider_type") String providerType,
        @NotBlank @Size(max = 64) @JsonProperty("operation_code") String operationCode,
        @Size(max = 24) @JsonProperty("endpoint_type") String endpointType,
        @Size(max = 8) @JsonProperty("request_method") String requestMethod,
        @NotBlank @Size(max = 2_048) @JsonProperty("base_url") String baseUrl,
        @Size(max = 256) @JsonProperty("health_probe_path") String healthProbePath,
        @Size(max = 4_096) String credential,
        @Size(max = 2_048) @JsonProperty("proxy_url") String proxyUrl,
        @NotBlank @Size(max = 24) String status,
        @Min(100) @Max(600_000) @JsonProperty("timeout_ms") int timeoutMs,
        @Positive @Max(100_000) @JsonProperty("concurrency_limit") Integer concurrencyLimit,
        @PositiveOrZero @Max(1_000_000) int priority,
        @Positive @Max(1_000_000) int weight,
        @PositiveOrZero Long version
) {
}
