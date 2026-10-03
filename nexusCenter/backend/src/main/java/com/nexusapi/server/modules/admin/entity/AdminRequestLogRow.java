package com.nexusapi.server.modules.admin.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 管理员调用日志查询行；载荷已由网关递归脱敏并限长。 */
public class AdminRequestLogRow {
    /** 日志内部标识。 */
    private UUID id;
    /** 平台请求标识。 */
    private String requestId;
    /** 发起请求的用户标识。 */
    private UUID userId;
    /** 用户展示名。 */
    private String userDisplayName;
    /** 用户令牌名称。 */
    private String apiKeyName;
    /** 服务分组名称。 */
    private String serviceGroupName;
    /** 最终处理请求的供应商代码。 */
    private String supplierCode;
    /** 最终处理请求的供应商名称。 */
    private String supplierName;
    /** 请求开始时间。 */
    private Instant startedAt;
    /** 请求完成时间。 */
    private Instant completedAt;
    /** 平台耗时。 */
    private Long durationMs;
    /** 对外模型名。 */
    private String publicModel;
    /** 最终 HTTP 状态码。 */
    private Integer statusCode;
    /** 输入 Token 数。 */
    private long inputTokens;
    /** 输出 Token 数。 */
    private long outputTokens;
    /** 缓存 Token 数。 */
    private long cachedTokens;
    /** 计费金额。 */
    private BigDecimal billedAmount;
    /** 是否流式请求。 */
    private boolean streaming;
    /** 重试次数。 */
    private int retryCount;
    /** 平台错误码。 */
    private String platformErrorCode;
    /** 请求摘要 JSON。 */
    private String requestSummaryJson;
    /** 返回摘要 JSON。 */
    private String responseSummaryJson;
    /** 递归脱敏后的请求 JSON。 */
    private String requestDetailJson;
    /** 递归脱敏后的返回 JSON。 */
    private String responseDetailJson;
    /** 原始请求体大小。 */
    private long requestPayloadSize;
    /** 原始返回体大小。 */
    private long responsePayloadSize;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getUserDisplayName() { return userDisplayName; }
    public void setUserDisplayName(String userDisplayName) { this.userDisplayName = userDisplayName; }
    public String getApiKeyName() { return apiKeyName; }
    public void setApiKeyName(String apiKeyName) { this.apiKeyName = apiKeyName; }
    public String getServiceGroupName() { return serviceGroupName; }
    public void setServiceGroupName(String serviceGroupName) { this.serviceGroupName = serviceGroupName; }
    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public String getPublicModel() { return publicModel; }
    public void setPublicModel(String publicModel) { this.publicModel = publicModel; }
    public Integer getStatusCode() { return statusCode; }
    public void setStatusCode(Integer statusCode) { this.statusCode = statusCode; }
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
    public String getPlatformErrorCode() { return platformErrorCode; }
    public void setPlatformErrorCode(String platformErrorCode) { this.platformErrorCode = platformErrorCode; }
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
