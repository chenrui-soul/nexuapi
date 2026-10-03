package com.nexusapi.server.modules.dashboard.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 管理端经营分析总览，所有金额均来自请求完成时的历史快照。 */
public record AdminDashboardOverviewResponse(
        String preset,
        Instant from,
        Instant to,
        @JsonProperty("bucket_size") String bucketSize,
        @JsonProperty("last_aggregated_at") Instant lastAggregatedAt,
        @JsonProperty("settlement_currency") String settlementCurrency,
        Summary summary,
        List<TrendPoint> trend,
        Rankings rankings,
        @JsonProperty("error_distribution") List<ErrorDistributionItem> errorDistribution
) {
    /** 请求、资金、延迟和上游稳定性的区间汇总。 */
    public record Summary(
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("failure_count") long failureCount,
            @JsonProperty("supplier_failure_count") long supplierFailureCount,
            @JsonProperty("success_rate") BigDecimal successRate,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cached_tokens") long cachedTokens,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            @JsonProperty("supplier_cost_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal supplierCostAmount,
            @JsonProperty("gross_margin_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossMarginAmount,
            @JsonProperty("gross_margin_rate") BigDecimal grossMarginRate,
            @JsonProperty("average_latency_ms") BigDecimal averageLatencyMs,
            @JsonProperty("latency_p95_ms") Long latencyP95Ms,
            @JsonProperty("attempt_count") long attemptCount,
            @JsonProperty("attempt_success_count") long attemptSuccessCount,
            @JsonProperty("attempt_supplier_failure_count") long attemptSupplierFailureCount,
            @JsonProperty("attempt_success_rate") BigDecimal attemptSuccessRate,
            @JsonProperty("attempt_average_latency_ms") BigDecimal attemptAverageLatencyMs,
            @JsonProperty("attempt_latency_p95_ms") Long attemptLatencyP95Ms
    ) {
    }

    /** 收入、成本、毛利和请求质量的单个时间桶。 */
    public record TrendPoint(
            @JsonProperty("bucket_start") Instant bucketStart,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("failure_count") long failureCount,
            @JsonProperty("success_rate") BigDecimal successRate,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            @JsonProperty("supplier_cost_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal supplierCostAmount,
            @JsonProperty("gross_margin_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossMarginAmount,
            @JsonProperty("gross_margin_rate") BigDecimal grossMarginRate,
            @JsonProperty("latency_p95_ms") Long latencyP95Ms
    ) {
    }

    /** 四个核心业务维度的经营排名。 */
    public record Rankings(
            List<RankingItem> suppliers,
            List<RankingItem> models,
            List<RankingItem> channels,
            List<RankingItem> groups
    ) {
    }

    /** 单个供应商、模型、渠道或分组的经营指标。 */
    public record RankingItem(
            @JsonProperty("dimension_id") String dimensionId,
            @JsonProperty("dimension_name") String dimensionName,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("failure_count") long failureCount,
            @JsonProperty("success_rate") BigDecimal successRate,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cached_tokens") long cachedTokens,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            @JsonProperty("supplier_cost_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal supplierCostAmount,
            @JsonProperty("gross_margin_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal grossMarginAmount,
            @JsonProperty("gross_margin_rate") BigDecimal grossMarginRate,
            @JsonProperty("average_latency_ms") BigDecimal averageLatencyMs,
            @JsonProperty("attempt_count") long attemptCount,
            @JsonProperty("attempt_success_count") long attemptSuccessCount,
            @JsonProperty("attempt_supplier_failure_count") long attemptSupplierFailureCount,
            @JsonProperty("attempt_success_rate") BigDecimal attemptSuccessRate
    ) {
    }

    /** 脱敏后的上游失败分类及出现次数。 */
    public record ErrorDistributionItem(
            String category,
            @JsonProperty("occurrence_count") long occurrenceCount
    ) {
    }
}
