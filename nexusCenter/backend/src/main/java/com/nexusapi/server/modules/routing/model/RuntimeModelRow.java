package com.nexusapi.server.modules.routing.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Gateway 运行时使用的模型只读快照。
 *
 * <p>该对象只包含协议校验、Token 估算和平台售价所需字段，不复用管理端 DTO。</p>
 */
public class RuntimeModelRow {
    /** 平台模型主键，用于 API 令牌模型白名单和调用日志关联。 */
    private UUID id;
    /** 客户端请求中使用的公开模型名称。 */
    private String publicName;
    /** 模型提供方标识，仅用于 OpenAI 模型目录的 owned_by 字段。 */
    private String provider;
    /** 模型能力类型，用于阻止文本、图片等不同 Gateway 入口交叉调用。 */
    private String capabilityType;
    /** 模型上下文窗口 Token 上限；空值表示未配置。 */
    private Long contextWindow;
    /** 模型单次最大输出 Token；空值时使用 Gateway 默认值。 */
    private Long maxOutputTokens;
    /** 是否允许 SSE 流式调用。 */
    private boolean supportsStreaming;
    /** 平台输入 Token 售价，单位由 priceUnit 指定。 */
    private BigDecimal inputPrice;
    /** 平台输出 Token 售价，单位由 priceUnit 指定。 */
    private BigDecimal outputPrice;
    /** 平台缓存输入 Token 售价，单位由 priceUnit 指定。 */
    private BigDecimal cachedInputPrice;
    /** 当前仅支持 million_tokens，即每一百万 Token 的价格。 */
    private String priceUnit;
    /** 平台 V2 计费类型。 */
    private int billingType;
    /** 平台销售基准积分单价。 */
    private BigDecimal unitPrice;
    /** 仅展示、不参与扣费的划线原价。 */
    private BigDecimal displayOriginalPrice;
    /** 文本输入、文本输出、音频输入、音频输出和缓存输入 Token 倍率，万分位。 */
    private long inputTokenRatio;
    private long outputTokenRatio;
    private long audioInputTokenRatio;
    private long audioOutputTokenRatio;
    private long cachedInputTokenRatio;
    /** 缓存写入倍率，万分位；0 表示使用默认规则。 */
    private long cacheWrite5mTokenRatio;
    private long cacheWrite1hTokenRatio;
    /** 长上下文计价模式和条件规则未命中策略。 */
    private int contextTierMode;
    private String pricingUnmatchedBehavior;
    /** 当前生效价格版本；为空时 Gateway 使用旧字段。 */
    private UUID activePricingVersionId;
    /** 模型创建时间，用于 OpenAI 模型目录 created 字段。 */
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPublicName() { return publicName; }
    public void setPublicName(String publicName) { this.publicName = publicName; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public Long getContextWindow() { return contextWindow; }
    public void setContextWindow(Long contextWindow) { this.contextWindow = contextWindow; }
    public Long getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(Long maxOutputTokens) { this.maxOutputTokens = maxOutputTokens; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
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
    public int getContextTierMode() { return contextTierMode; }
    public void setContextTierMode(int contextTierMode) { this.contextTierMode = contextTierMode; }
    public String getPricingUnmatchedBehavior() { return pricingUnmatchedBehavior; }
    public void setPricingUnmatchedBehavior(String pricingUnmatchedBehavior) { this.pricingUnmatchedBehavior = pricingUnmatchedBehavior; }
    public UUID getActivePricingVersionId() { return activePricingVersionId; }
    public void setActivePricingVersionId(UUID activePricingVersionId) { this.activePricingVersionId = activePricingVersionId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
