package com.nexusapi.server.modules.requestlog.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 用户调用日志响应白名单，禁止加入供应商、渠道和内部路由字段。 */
public record UserRequestLogResponse(
        UUID id,
        @JsonProperty("request_id") String requestId,
        @JsonProperty("started_at") Instant startedAt,
        @JsonProperty("completed_at") Instant completedAt,
        @JsonProperty("duration_ms") Long durationMs,
        @JsonProperty("public_model") String publicModel,
        @JsonProperty("api_key_name") String apiKeyName,
        @JsonProperty("service_group_name") String serviceGroupName,
        @JsonProperty("status_code") Integer statusCode,
        String status,
        @JsonProperty("input_tokens") long inputTokens,
        @JsonProperty("output_tokens") long outputTokens,
        @JsonProperty("cached_tokens") long cachedTokens,
        @JsonProperty("billed_amount") BigDecimal billedAmount,
        boolean streaming,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("failure_reason") String failureReason,
        @JsonProperty("request_summary") String requestSummary,
        @JsonProperty("response_summary") String responseSummary,
        @JsonProperty("request_payload_size") long requestPayloadSize,
        @JsonProperty("response_payload_size") long responsePayloadSize
) {
}
