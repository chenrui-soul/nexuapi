package com.nexusapi.server.modules.dashboard.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** 用户仪表盘响应，只包含当前用户自己的调用聚合和安全业务名称。 */
public record UserDashboardOverviewResponse(
        String preset,
        Instant from,
        Instant to,
        @JsonProperty("bucket_size") String bucketSize,
        @JsonProperty("updated_at") Instant updatedAt,
        Summary summary,
        Comparison comparison,
        List<TrendPoint> trend,
        @JsonProperty("capability_distribution") List<CapabilityItem> capabilityDistribution,
        Rankings rankings,
        @JsonProperty("recent_requests") List<RecentRequest> recentRequests,
        @JsonProperty("activity_heatmap") List<ActivityPoint> activityHeatmap,
        @JsonProperty("live_metrics") LiveMetrics liveMetrics
) {
    /** 当前统计区间核心指标。 */
    public record Summary(
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("failure_count") long failureCount,
            @JsonProperty("success_rate") BigDecimal successRate,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cached_tokens") long cachedTokens,
            @JsonProperty("cache_hit_rate") BigDecimal cacheHitRate,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            @JsonProperty("average_billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal averageBilledAmount,
            @JsonProperty("average_latency_ms") BigDecimal averageLatencyMs,
            @JsonProperty("latency_p50_ms") Long latencyP50Ms,
            @JsonProperty("latency_p95_ms") Long latencyP95Ms
    ) {
    }

    /** 与上一等长区间的真实变化率；上一期为零时返回空。 */
    public record Comparison(
            @JsonProperty("request_change_rate") BigDecimal requestChangeRate,
            @JsonProperty("billed_change_rate") BigDecimal billedChangeRate
    ) {
    }

    /** 单个趋势时间桶。 */
    public record TrendPoint(
            @JsonProperty("bucket_start") Instant bucketStart,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("success_count") long successCount,
            @JsonProperty("failure_count") long failureCount,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cached_tokens") long cachedTokens
    ) {
    }

    /** 文本、图片、视频、音频等能力的积分分布。 */
    public record CapabilityItem(
            @JsonProperty("capability_type") String capabilityType,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            BigDecimal percentage
    ) {
    }

    /** 用户可见的三个消费排行维度。 */
    public record Rankings(
            List<RankingItem> models,
            @JsonProperty("api_keys") List<RankingItem> apiKeys,
            List<RankingItem> groups
    ) {
    }

    /** 单个消费排行条目。 */
    public record RankingItem(
            @JsonProperty("dimension_id") String dimensionId,
            @JsonProperty("dimension_name") String dimensionName,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            BigDecimal percentage
    ) {
    }

    /** 仪表盘最近请求白名单。 */
    public record RecentRequest(
            @JsonProperty("request_id") String requestId,
            @JsonProperty("started_at") Instant startedAt,
            @JsonProperty("public_model") String publicModel,
            @JsonProperty("api_key_name") String apiKeyName,
            @JsonProperty("service_group_name") String serviceGroupName,
            @JsonProperty("status_code") Integer statusCode,
            String status,
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens,
            @JsonProperty("cached_tokens") long cachedTokens,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount,
            @JsonProperty("duration_ms") Long durationMs,
            boolean streaming,
            @JsonProperty("retry_count") int retryCount,
            @JsonProperty("failure_reason") String failureReason
    ) {
    }

    /** 星期和小时维度的真实请求热力点。 */
    public record ActivityPoint(
            @JsonProperty("day_of_week") int dayOfWeek,
            @JsonProperty("hour_of_day") int hourOfDay,
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("billed_amount") @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal billedAmount
    ) {
    }

    /** 当前用户最近一分钟的真实请求指标。 */
    public record LiveMetrics(
            @JsonProperty("request_count") long requestCount,
            @JsonProperty("requests_per_second") BigDecimal requestsPerSecond,
            long rpm,
            @JsonProperty("tokens_per_minute") long tokensPerMinute,
            @JsonProperty("cached_tokens") long cachedTokens,
            @JsonProperty("billed_amount_per_minute") @JsonFormat(shape = JsonFormat.Shape.STRING)
            BigDecimal billedAmountPerMinute
    ) {
    }
}
