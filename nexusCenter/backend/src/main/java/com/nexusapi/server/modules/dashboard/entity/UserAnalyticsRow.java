package com.nexusapi.server.modules.dashboard.entity;

import java.math.BigDecimal;
import java.time.Instant;

/** 用户仪表盘与分组状态查询使用的数据库行集合，所有字段均为用户安全聚合字段。 */
public final class UserAnalyticsRow {
    private UserAnalyticsRow() {
    }

    /** 当前用户在统计区间内的请求汇总。 */
    public static class Summary {
        /** 请求总数。 */
        private long requestCount;
        /** 成功请求数。 */
        private long successCount;
        /** 失败请求数。 */
        private long failureCount;
        /** 输入 Token 总数。 */
        private long inputTokens;
        /** 输出 Token 总数。 */
        private long outputTokens;
        /** 缓存输入 Token 总数。 */
        private long cachedTokens;
        /** 实际结算积分。 */
        private BigDecimal billedAmount;
        /** 有耗时记录的请求数。 */
        private long latencyCount;
        /** 平台总耗时合计，单位毫秒。 */
        private long latencySumMs;
        /** 平台总耗时 P50，单位毫秒。 */
        private Long latencyP50Ms;
        /** 平台总耗时 P95，单位毫秒。 */
        private Long latencyP95Ms;

        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long value) { requestCount = value; }
        public long getSuccessCount() { return successCount; }
        public void setSuccessCount(long value) { successCount = value; }
        public long getFailureCount() { return failureCount; }
        public void setFailureCount(long value) { failureCount = value; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long value) { inputTokens = value; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long value) { outputTokens = value; }
        public long getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(long value) { cachedTokens = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
        public long getLatencyCount() { return latencyCount; }
        public void setLatencyCount(long value) { latencyCount = value; }
        public long getLatencySumMs() { return latencySumMs; }
        public void setLatencySumMs(long value) { latencySumMs = value; }
        public Long getLatencyP50Ms() { return latencyP50Ms; }
        public void setLatencyP50Ms(Long value) { latencyP50Ms = value; }
        public Long getLatencyP95Ms() { return latencyP95Ms; }
        public void setLatencyP95Ms(Long value) { latencyP95Ms = value; }
    }

    /** 当前用户趋势图的单个时间桶。 */
    public static class Trend {
        /** 时间桶起点。 */
        private Instant bucketStart;
        /** 请求数。 */
        private long requestCount;
        /** 成功数。 */
        private long successCount;
        /** 失败数。 */
        private long failureCount;
        /** 实扣积分。 */
        private BigDecimal billedAmount;
        /** 输入 Token。 */
        private long inputTokens;
        /** 输出 Token。 */
        private long outputTokens;
        /** 缓存 Token。 */
        private long cachedTokens;

        public Instant getBucketStart() { return bucketStart; }
        public void setBucketStart(Instant value) { bucketStart = value; }
        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long value) { requestCount = value; }
        public long getSuccessCount() { return successCount; }
        public void setSuccessCount(long value) { successCount = value; }
        public long getFailureCount() { return failureCount; }
        public void setFailureCount(long value) { failureCount = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long value) { inputTokens = value; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long value) { outputTokens = value; }
        public long getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(long value) { cachedTokens = value; }
    }

    /** 模型、API Key 或服务分组的用户消费排行。 */
    public static class Ranking {
        /** 维度 ID；资源已删除时为稳定回退值。 */
        private String dimensionId;
        /** 用户可读名称。 */
        private String dimensionName;
        /** 请求数。 */
        private long requestCount;
        /** 实扣积分。 */
        private BigDecimal billedAmount;

        public String getDimensionId() { return dimensionId; }
        public void setDimensionId(String value) { dimensionId = value; }
        public String getDimensionName() { return dimensionName; }
        public void setDimensionName(String value) { dimensionName = value; }
        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long value) { requestCount = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
    }

    /** 模型能力类型的积分分布。 */
    public static class Capability {
        /** 平台能力类型。 */
        private String capabilityType;
        /** 请求数。 */
        private long requestCount;
        /** 实扣积分。 */
        private BigDecimal billedAmount;

        public String getCapabilityType() { return capabilityType; }
        public void setCapabilityType(String value) { capabilityType = value; }
        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long value) { requestCount = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
    }

    /** 用户仪表盘最近调用白名单行。 */
    public static class RecentRequest {
        /** 请求 ID。 */
        private String requestId;
        /** 请求开始时间。 */
        private Instant startedAt;
        /** 平台公开模型名。 */
        private String publicModel;
        /** API Key 名称。 */
        private String apiKeyName;
        /** 服务分组名称。 */
        private String serviceGroupName;
        /** 返回给用户的 HTTP 状态码。 */
        private Integer statusCode;
        /** 平台安全错误码。 */
        private String platformErrorCode;
        /** 输入 Token。 */
        private long inputTokens;
        /** 输出 Token。 */
        private long outputTokens;
        /** 缓存 Token。 */
        private long cachedTokens;
        /** 实扣积分。 */
        private BigDecimal billedAmount;
        /** 平台总耗时，单位毫秒。 */
        private Long durationMs;
        /** 是否流式。 */
        private boolean streaming;
        /** 重试次数。 */
        private int retryCount;

        public String getRequestId() { return requestId; }
        public void setRequestId(String value) { requestId = value; }
        public Instant getStartedAt() { return startedAt; }
        public void setStartedAt(Instant value) { startedAt = value; }
        public String getPublicModel() { return publicModel; }
        public void setPublicModel(String value) { publicModel = value; }
        public String getApiKeyName() { return apiKeyName; }
        public void setApiKeyName(String value) { apiKeyName = value; }
        public String getServiceGroupName() { return serviceGroupName; }
        public void setServiceGroupName(String value) { serviceGroupName = value; }
        public Integer getStatusCode() { return statusCode; }
        public void setStatusCode(Integer value) { statusCode = value; }
        public String getPlatformErrorCode() { return platformErrorCode; }
        public void setPlatformErrorCode(String value) { platformErrorCode = value; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long value) { inputTokens = value; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long value) { outputTokens = value; }
        public long getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(long value) { cachedTokens = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
        public Long getDurationMs() { return durationMs; }
        public void setDurationMs(Long value) { durationMs = value; }
        public boolean isStreaming() { return streaming; }
        public void setStreaming(boolean value) { streaming = value; }
        public int getRetryCount() { return retryCount; }
        public void setRetryCount(int value) { retryCount = value; }
    }

    /** 星期与小时维度的真实活动热力数据。 */
    public static class Activity {
        /** ISO 星期：1 为周一，7 为周日。 */
        private int dayOfWeek;
        /** 小时：0 至 23。 */
        private int hourOfDay;
        /** 请求数。 */
        private long requestCount;
        /** 实扣积分。 */
        private BigDecimal billedAmount;

        public int getDayOfWeek() { return dayOfWeek; }
        public void setDayOfWeek(int value) { dayOfWeek = value; }
        public int getHourOfDay() { return hourOfDay; }
        public void setHourOfDay(int value) { hourOfDay = value; }
        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long value) { requestCount = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
    }

    /** 用户最近一分钟的真实请求指标。 */
    public static class Live {
        /** 最近一分钟请求数。 */
        private long requestCount;
        /** 最近一分钟输入 Token。 */
        private long inputTokens;
        /** 最近一分钟输出 Token。 */
        private long outputTokens;
        /** 最近一分钟缓存 Token。 */
        private long cachedTokens;
        /** 最近一分钟实扣积分。 */
        private BigDecimal billedAmount;

        public long getRequestCount() { return requestCount; }
        public void setRequestCount(long value) { requestCount = value; }
        public long getInputTokens() { return inputTokens; }
        public void setInputTokens(long value) { inputTokens = value; }
        public long getOutputTokens() { return outputTokens; }
        public void setOutputTokens(long value) { outputTokens = value; }
        public long getCachedTokens() { return cachedTokens; }
        public void setCachedTokens(long value) { cachedTokens = value; }
        public BigDecimal getBilledAmount() { return billedAmount; }
        public void setBilledAmount(BigDecimal value) { billedAmount = value; }
    }

    /** 用户可见服务分组的最近 60 次真实请求汇总。 */
    public static class GroupStatus {
        /** 服务分组 ID。 */
        private String groupId;
        /** 服务分组名称。 */
        private String name;
        /** 用户可见说明。 */
        private String description;
        /** 用户售价倍率。 */
        private BigDecimal priceMultiplier;
        /** 当前有效模型数。 */
        private long availableModelCount;
        /** 当前目录模型总数。 */
        private long totalModelCount;
        /** 最近样本数，最大 60。 */
        private long sampleCount;
        /** 最近样本成功数。 */
        private long successCount;
        /** 有耗时的样本数。 */
        private long latencyCount;
        /** 样本耗时合计，单位毫秒。 */
        private long latencySumMs;
        /** 样本耗时 P95，单位毫秒。 */
        private Long latencyP95Ms;
        /** 最近一次真实请求时间。 */
        private Instant lastRequestAt;

        public String getGroupId() { return groupId; }
        public void setGroupId(String value) { groupId = value; }
        public String getName() { return name; }
        public void setName(String value) { name = value; }
        public String getDescription() { return description; }
        public void setDescription(String value) { description = value; }
        public BigDecimal getPriceMultiplier() { return priceMultiplier; }
        public void setPriceMultiplier(BigDecimal value) { priceMultiplier = value; }
        public long getAvailableModelCount() { return availableModelCount; }
        public void setAvailableModelCount(long value) { availableModelCount = value; }
        public long getTotalModelCount() { return totalModelCount; }
        public void setTotalModelCount(long value) { totalModelCount = value; }
        public long getSampleCount() { return sampleCount; }
        public void setSampleCount(long value) { sampleCount = value; }
        public long getSuccessCount() { return successCount; }
        public void setSuccessCount(long value) { successCount = value; }
        public long getLatencyCount() { return latencyCount; }
        public void setLatencyCount(long value) { latencyCount = value; }
        public long getLatencySumMs() { return latencySumMs; }
        public void setLatencySumMs(long value) { latencySumMs = value; }
        public Long getLatencyP95Ms() { return latencyP95Ms; }
        public void setLatencyP95Ms(Long value) { latencyP95Ms = value; }
        public Instant getLastRequestAt() { return lastRequestAt; }
        public void setLastRequestAt(Instant value) { lastRequestAt = value; }
    }

    /** 服务分组最近请求的安全状态记录。 */
    public static class GroupHistory {
        /** 服务分组 ID。 */
        private String groupId;
        /** 请求发生时间。 */
        private Instant occurredAt;
        /** 返回给用户的 HTTP 状态码。 */
        private Integer statusCode;
        /** 平台总耗时，单位毫秒。 */
        private Long durationMs;

        public String getGroupId() { return groupId; }
        public void setGroupId(String value) { groupId = value; }
        public Instant getOccurredAt() { return occurredAt; }
        public void setOccurredAt(Instant value) { occurredAt = value; }
        public Integer getStatusCode() { return statusCode; }
        public void setStatusCode(Integer value) { statusCode = value; }
        public Long getDurationMs() { return durationMs; }
        public void setDurationMs(Long value) { durationMs = value; }
    }
}
