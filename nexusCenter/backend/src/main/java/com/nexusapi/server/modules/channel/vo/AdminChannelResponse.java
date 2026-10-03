package com.nexusapi.server.modules.channel.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/** 渠道响应只暴露凭证状态和指纹，不返回密文或明文。 */
public record AdminChannelResponse(
        UUID id,
        @JsonProperty("supplier_id") UUID supplierId,
        @JsonProperty("supplier_code") String supplierCode,
        @JsonProperty("supplier_name") String supplierName,
        String name,
        @JsonProperty("provider_type") String providerType,
        @JsonProperty("operation_code") String operationCode,
        @JsonProperty("endpoint_type") String endpointType,
        @JsonProperty("request_method") String requestMethod,
        @JsonProperty("base_url") String baseUrl,
        @JsonProperty("health_probe_path") String healthProbePath,
        @JsonProperty("credential_configured") boolean credentialConfigured,
        @JsonProperty("credential_fingerprint") String credentialFingerprint,
        @JsonProperty("credential_updated_at") Instant credentialUpdatedAt,
        @JsonProperty("proxy_url") String proxyUrl,
        String status,
        @JsonProperty("timeout_ms") int timeoutMs,
        @JsonProperty("concurrency_limit") Integer concurrencyLimit,
        int priority,
        int weight,
        @JsonProperty("consecutive_failures") int consecutiveFailures,
        @JsonProperty("circuit_open_until") Instant circuitOpenUntil,
        @JsonProperty("last_error_summary") String lastErrorSummary,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        long version
) {
}
