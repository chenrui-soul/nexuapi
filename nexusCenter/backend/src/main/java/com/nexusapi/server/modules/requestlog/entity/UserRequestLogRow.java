package com.nexusapi.server.modules.requestlog.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 用户调用日志查询行，只包含控制台用户可见的脱敏字段。 */
public class UserRequestLogRow {
    /** 日志记录内部标识。 */
    private UUID id;
    /** 平台生成并贯穿网关、计费和上游尝试的请求标识。 */
    private String requestId;
    /** 平台开始处理请求的时间。 */
    private Instant startedAt;
    /** 请求完成或失败终止的时间。 */
    private Instant completedAt;
    /** 平台处理请求总耗时，单位毫秒。 */
    private Long durationMs;
    /** 用户请求的平台公开模型名。 */
    private String publicModel;
    /** 用户 API 令牌名称。 */
    private String apiKeyName;
    /** 本次调用采用的服务分组名称。 */
    private String serviceGroupName;
    /** 最终返回给用户的 HTTP 状态码。 */
    private Integer statusCode;
    /** 平台归一化错误码，仅用于服务端生成固定失败文案。 */
    private String platformErrorCode;
    /** 上游确认的输入 Token 数。 */
    private long inputTokens;
    /** 上游确认的输出 Token 数。 */
    private long outputTokens;
    /** 输入 Token 中命中缓存的数量。 */
    private long cachedTokens;
    /** 本次调用最终结算的平台积分。 */
    private BigDecimal billedAmount;
    /** 本次调用是否使用 SSE 流式响应。 */
    private boolean streaming;
    /** 发生可重试上游错误后实际执行的重试次数。 */
    private int retryCount;
    /** 脱敏后的请求参数摘要 JSON，仅详情接口返回。 */
    private String requestSummaryJson;
    /** 脱敏后的响应结果摘要 JSON，仅详情接口返回。 */
    private String responseSummaryJson;
    /** 管理员排障用的递归脱敏完整请求参数。 */
    private String requestDetailJson;
    /** 管理员排障用的递归脱敏完整返回结果。 */
    private String responseDetailJson;
    /** 原始请求体大小，单位字节；不保存原文。 */
    private long requestPayloadSize;
    /** 原始响应体大小，单位字节；不保存原文。 */
    private long responsePayloadSize;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getPublicModel() { return publicModel; }
    public void setPublicModel(String publicModel) { this.publicModel = publicModel; }
    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String apiKeyName) { this.apiKeyName = apiKeyName; }
    public String getServiceGroupName() { return serviceGroupName; }
    public void setServiceGroupName(String serviceGroupName) { this.serviceGroupName = serviceGroupName; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
    public String getPlatformErrorCode() { return platformErrorCode; }
    public void setPlatformErrorCode(String platformErrorCode) { this.platformErrorCode = platformErrorCode; }
    public long getInputTokens() { return inputTokens; }
    public void setInputTokens(long inputTokens) { this.inputTokens = inputTokens; }
    public long getOutputTokens() { return outputTokens; }
    public void setOutputTokens(long outputTokens) { this.outputTokens = outputTokens; }
    public long getCachedTokens() { return cachedTokens; }
    public void setCachedTokens(long cachedTokens) { this.cachedTokens = cachedTokens; }
    public BigDecimal getBilledAmount() { return billedAmount; }
    public void setBilledAmount(BigDecimal billedAmount) { this.billedAmount = billedAmount; }
    public boolean isStreaming() { return streaming; }
    public void setStreaming(boolean streaming) { this.streaming = streaming; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public String getRequestSummaryJson() { return requestSummaryJson; }
    public void setRequestSummaryJson(String requestSummaryJson) { this.requestSummaryJson = requestSummaryJson; }
    public String getResponseSummaryJson() { return responseSummaryJson; }
    public void setResponseSummaryJson(String responseSummaryJson) { this.responseSummaryJson = responseSummaryJson; }
    public String getRequestDetailJson() { return requestDetailJson; }
    public void setRequestDetailJson(String requestDetailJson) { this.requestDetailJson = requestDetailJson; }
    public String getResponseDetailJson() { return responseDetailJson; }
    public void setResponseDetailJson(String responseDetailJson) { this.responseDetailJson = responseDetailJson; }
    public long getRequestPayloadSize() { return requestPayloadSize; }
    public void setRequestPayloadSize(long requestPayloadSize) { this.requestPayloadSize = requestPayloadSize; }
    public long getResponsePayloadSize() { return responsePayloadSize; }
    public void setResponsePayloadSize(long responsePayloadSize) { this.responsePayloadSize = responsePayloadSize; }
}
