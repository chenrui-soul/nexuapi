package com.nexusapi.server.modules.model.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** MyBatis 使用的模型配置行，不直接作为 HTTP 响应返回。 */
public class AdminModelRow {
    /** 模型配置全局唯一标识。 */
    private UUID id;
    /** 对外兼容 OpenAI 协议的模型名称，例如 gpt-5.6-sol。 */
    private String publicName;
    /** 管理端和模型目录显示的可读名称。 */
    private String displayName;
    /** 模型归属提供商标识，例如 openai、anthropic。 */
    private String provider;
    /** 能力类型：text、image、audio、video、embedding 或 multimodal。 */
    private String capabilityType;
    /** 业务层协议适配器注册键，由模型维护页面统一配置。 */
    private String adapterKey;
    /** 支持的输入模态 JSON 数组。 */
    private String inputModalitiesJson;
    /** 支持的输出模态 JSON 数组。 */
    private String outputModalitiesJson;
    /** 最大上下文 Token 数；上游未提供明确限制时可为 null。 */
    private Long contextWindow;
    /** 单次响应允许的最大输出 Token 数；上游未提供明确限制时可为 null。 */
    private Long maxOutputTokens;
    /** 是否支持 SSE 流式响应。 */
    private boolean supportsStreaming;
    /** 是否支持工具或函数调用。 */
    private boolean supportsTools;
    /** 是否支持 JSON Schema 等结构化输出。 */
    private boolean supportsStructuredOutput;
    /** 输入 Token 对用户的基础售价，计价单位由 priceUnit 指定。 */
    private BigDecimal inputPrice;
    /** 输出 Token 对用户的基础售价，计价单位由 priceUnit 指定。 */
    private BigDecimal outputPrice;
    /** 命中缓存的输入 Token 基础售价，计价单位由 priceUnit 指定。 */
    private BigDecimal cachedInputPrice;
    /** 价格计量单位，例如 million_tokens 表示每百万 Token。 */
    private String priceUnit;
    /** 平台计费类型：1 按次、2 按图片张数、3 按视频秒、4 按 Token、5 按字符、6 按音频秒。 */
    private int billingType;
    /** 当前生效价格版本的平台销售基准积分单价。 */
    private BigDecimal unitPrice;
    /** 仅用于划线展示的原价，不参与实际计费。 */
    private BigDecimal displayOriginalPrice;
    /** 输入 Token 倍率，万分位。 */
    private long inputTokenRatio;
    /** 输出 Token 倍率，万分位。 */
    private long outputTokenRatio;
    /** 音频输入 Token 倍率，万分位；只用于按 Token 的多模态模型。 */
    private long audioInputTokenRatio;
    /** 音频输出 Token 倍率，万分位；只用于按 Token 的多模态模型。 */
    private long audioOutputTokenRatio;
    /** 缓存命中输入 Token 倍率，万分位。 */
    private long cachedInputTokenRatio;
    /** 5 分钟缓存写入倍率，万分位；0 表示采用输入倍率的 1.25 倍。 */
    private long cacheWrite5mTokenRatio;
    /** 1 小时缓存写入倍率，万分位；0 表示采用输入倍率的 2 倍。 */
    private long cacheWrite1hTokenRatio;
    /** 管理员维护的计费说明。 */
    private String chargeDesc;
    /** 当前生效的不可变价格版本；为空表示继续使用旧价格字段。 */
    private UUID activePricingVersionId;
    /** 是否允许未登录用户或普通用户在公开模型目录中看到该模型。 */
    private boolean publicVisible;
    /** 模型状态：active、disabled 或 maintenance。 */
    private String status;
    /** 模型同步来源；人工创建且未匹配来源时为空。 */
    private String syncSource;
    /** 上游模型市场稳定键。 */
    private String sourceModelKey;
    /** 是否允许同步任务更新基础能力字段。 */
    private boolean sourceManaged;
    /** 最近一次在上游完整快照中发现该模型的时间。 */
    private Instant sourceLastSeenAt;
    /** 最近一次写入上游快照的时间。 */
    private Instant sourceSyncedAt;
    /** 该模型所属服务分组及上游健康摘要 JSON 数组。 */
    private String serviceGroupsJson;
    /** 该模型已支持的 API 接口摘要 JSON 数组。 */
    private String interfacesJson;
    /** 模型配置创建时间。 */
    private Instant createdAt;
    /** 模型配置最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号，防止并发修改价格或能力配置时相互覆盖。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPublicName() { return publicName; }
    public void setPublicName(String publicName) { this.publicName = publicName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public String getAdapterKey() { return adapterKey; }
    public void setAdapterKey(String adapterKey) { this.adapterKey = adapterKey; }
    public String getInputModalitiesJson() { return inputModalitiesJson; }
    public void setInputModalitiesJson(String inputModalitiesJson) { this.inputModalitiesJson = inputModalitiesJson; }
    public String getOutputModalitiesJson() { return outputModalitiesJson; }
    public void setOutputModalitiesJson(String outputModalitiesJson) { this.outputModalitiesJson = outputModalitiesJson; }
    public Long getContextWindow() { return contextWindow; }
    public void setContextWindow(Long contextWindow) { this.contextWindow = contextWindow; }
    public Long getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Long maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
    public boolean isSupportsTools() { return supportsTools; }
    public void setSupportsTools(boolean supportsTools) { this.supportsTools = supportsTools; }
    public boolean isSupportsStructuredOutput() { return supportsStructuredOutput; }
    public void setSupportsStructuredOutput(boolean supportsStructuredOutput) { this.supportsStructuredOutput = supportsStructuredOutput; }
    public BigDecimal getInputPrice() { return inputPrice; }
    public void setInputPrice(BigDecimal inputPrice) { this.inputPrice = inputPrice; }
    public BigDecimal getOutputPrice() { return outputPrice; }
    public void setOutputPrice(BigDecimal outputPrice) { this.outputPrice = outputPrice; }
    public BigDecimal getCachedInputPrice() { return cachedInputPrice; }
    public void setCachedInputPrice(BigDecimal cachedInputPrice) { this.cachedInputPrice = cachedInputPrice; }
    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }
    public int getBillingType() { return billingType; }
    public void setBillingType(int billingType) { this.billingType = billingType; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public BigDecimal getDisplayOriginalPrice() { return displayOriginalPrice; }
    public void setDisplayOriginalPrice(BigDecimal displayOriginalPrice) { this.displayOriginalPrice = displayOriginalPrice; }
    public long getInputTokenRatio() { return inputTokenRatio; }
    public void setInputTokenRatio(long inputTokenRatio) { this.inputTokenRatio = inputTokenRatio; }
    public long getOutputTokenRatio() { return outputTokenRatio; }
    public void setOutputTokenRatio(long outputTokenRatio) { this.outputTokenRatio = outputTokenRatio; }
    public long getAudioInputTokenRatio() { return audioInputTokenRatio; }
    public void setAudioInputTokenRatio(long audioInputTokenRatio) { this.audioInputTokenRatio = audioInputTokenRatio; }
    public long getAudioOutputTokenRatio() { return audioOutputTokenRatio; }
    public void setAudioOutputTokenRatio(long audioOutputTokenRatio) { this.audioOutputTokenRatio = audioOutputTokenRatio; }
    public long getCachedInputTokenRatio() { return cachedInputTokenRatio; }
    public void setCachedInputTokenRatio(long cachedInputTokenRatio) { this.cachedInputTokenRatio = cachedInputTokenRatio; }
    public long getCacheWrite5mTokenRatio() { return cacheWrite5mTokenRatio; }
    public void setCacheWrite5mTokenRatio(long cacheWrite5mTokenRatio) { this.cacheWrite5mTokenRatio = cacheWrite5mTokenRatio; }
    public long getCacheWrite1hTokenRatio() { return cacheWrite1hTokenRatio; }
    public void setCacheWrite1hTokenRatio(long cacheWrite1hTokenRatio) { this.cacheWrite1hTokenRatio = cacheWrite1hTokenRatio; }
    public String getChargeDesc() { return chargeDesc; }
    public void setChargeDesc(String chargeDesc) { this.chargeDesc = chargeDesc; }
    public UUID getActivePricingVersionId() { return activePricingVersionId; }
    public void setActivePricingVersionId(UUID activePricingVersionId) { this.activePricingVersionId = activePricingVersionId; }
    public boolean isPublicVisible() { return publicVisible; }
    public void setPublicVisible(boolean publicVisible) { this.publicVisible = publicVisible; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSyncSource() { return syncSource; }
    public void setSyncSource(String syncSource) { this.syncSource = syncSource; }
    public String getSourceModelKey() { return sourceModelKey; }
    public void setSourceModelKey(String sourceModelKey) { this.sourceModelKey = sourceModelKey; }
    public boolean isSourceManaged() { return sourceManaged; }
    public void setSourceManaged(boolean sourceManaged) { this.sourceManaged = sourceManaged; }
    public Instant getSourceLastSeenAt() { return sourceLastSeenAt; }
    public void setSourceLastSeenAt(Instant sourceLastSeenAt) { this.sourceLastSeenAt = sourceLastSeenAt; }
    public Instant getSourceSyncedAt() { return sourceSyncedAt; }
    public void setSourceSyncedAt(Instant sourceSyncedAt) { this.sourceSyncedAt = sourceSyncedAt; }
    public String getServiceGroupsJson() { return serviceGroupsJson; }
    public void setServiceGroupsJson(String serviceGroupsJson) { this.serviceGroupsJson = serviceGroupsJson; }
    public String getInterfacesJson() { return interfacesJson; }
    public void setInterfacesJson(String interfacesJson) { this.interfacesJson = interfacesJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
