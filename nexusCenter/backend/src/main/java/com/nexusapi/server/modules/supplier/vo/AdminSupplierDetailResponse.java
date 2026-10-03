package com.nexusapi.server.modules.supplier.vo;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 管理端供应商详情聚合响应。
 * 财务值以十进制字符串返回，避免浏览器浮点转换破坏账务精度。
 */
public record AdminSupplierDetailResponse(
        AdminSupplierResponse supplier,
        Period period,
        Summary summary,
        List<Channel> channels,
        List<ModelMapping> models
) {
    /** 固定统计窗口，目前为请求时刻向前 30 天。 */
    public record Period(Instant from, Instant to, int days) { }

    /** 供应商资源、经营和稳定性指标。 */
    public record Summary(
            @JsonProperty("channel_count") long channelCount,
            @JsonProperty("available_channel_count") long availableChannelCount,
            @JsonProperty("model_count") long modelCount,
            @JsonProperty("active_model_count") long activeModelCount,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("success_rate") double successRate,
            @JsonProperty("billed_amount") String billedAmount,
            @JsonProperty("supplier_cost_amount") String supplierCostAmount,
            @JsonProperty("gross_margin_amount") String grossMarginAmount,
            @JsonProperty("attempt_count") long attemptCount,
            @JsonProperty("attempt_success_count") long attemptSuccessCount,
            @JsonProperty("attempt_success_rate") double attemptSuccessRate,
            @JsonProperty("attempt_supplier_failure_count") long attemptSupplierFailureCount
    ) { }

    /** 供应商关联渠道的安全摘要，不暴露凭证、代理地址或异常原文。 */
    public record Channel(
            UUID id,
            String name,
            @JsonProperty("provider_type") String providerType,
            @JsonProperty("base_url_origin") String baseUrlOrigin,
            String status,
            @JsonProperty("consecutive_failures") int consecutiveFailures,
            @JsonProperty("circuit_open_until") Instant circuitOpenUntil,
            @JsonProperty("mapping_count") long mappingCount,
            @JsonProperty("last_attempt_outcome") String lastAttemptOutcome,
            @JsonProperty("last_error_category") String lastErrorCategory,
            @JsonProperty("last_attempt_at") Instant lastAttemptAt
    ) { }

    /** 供应商通过渠道提供的平台模型映射及成本配置。 */
    public record ModelMapping(
            @JsonProperty("mapping_id") UUID mappingId,
            @JsonProperty("model_id") UUID modelId,
            @JsonProperty("public_name") String publicName,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("upstream_model") String upstreamModel,
            @JsonProperty("channel_id") UUID channelId,
            @JsonProperty("channel_name") String channelName,
            @JsonProperty("cost_input_price") String costInputPrice,
            @JsonProperty("cost_cached_input_price") String costCachedInputPrice,
            @JsonProperty("cost_output_price") String costOutputPrice,
            String status,
            @JsonProperty("updated_at") Instant updatedAt
    ) { }
}
