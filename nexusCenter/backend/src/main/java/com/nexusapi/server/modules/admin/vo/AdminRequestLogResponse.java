package com.nexusapi.server.modules.admin.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 管理端调用日志响应；仅返回已脱敏的业务请求与响应载荷。 */
public record AdminRequestLogResponse(
        UUID id,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("user_id") UUID userId,
        @JsonProperty("user_display_name") String userDisplayName,
        @JsonProperty("api_key_name") String apiKeyName,
        @JsonProperty("service_group_name") String serviceGroupName,
        @JsonProperty("supplier_code") String supplierCode,
        @JsonProperty("supplier_name") String supplierName,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("public_model") String publicModel,
        @JsonProperty("status_code") Integer statusCode,
        String status,
        @JsonProperty("input_tokens") long inputTokens,
        @JsonProperty("output_tokens") long outputTokens,
        @JsonProperty("cached_tokens") long cachedTokens,
        @JsonProperty("billed_amount") BigDecimal billedAmount,
        boolean streaming,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("platform_error_code") String platformErrorCode,
        @JsonProperty("request_summary") JsonNode requestSummary,
        @JsonProperty("response_summary") JsonNode responseSummary,
        @JsonProperty("request_detail") JsonNode requestDetail,
        @JsonProperty("response_detail") JsonNode responseDetail,
        @JsonProperty("request_payload_size") long requestPayloadSize,
        @JsonProperty("response_payload_size") long responsePayloadSize
) {}
