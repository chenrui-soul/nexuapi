package com.nexusapi.server.modules.model.entity;

import java.time.Instant;
import java.util.UUID;

/** 由模型市场白名单字段生成的安全写入快照。 */
public class SyncedModelRow {
    /** 新模型本地全局唯一标识。 */
    private UUID id;
    /** 上游原始模型名称，同时作为本地公开模型名称。 */
    private String publicName;
    /** 上游显示名称。 */
    private String displayName;
    /** 本地能力类型。 */
    private String capabilityType;
    /** 本地输入模态 JSON 数组。 */
    private String inputModalitiesJson;
    /** 本地输出模态 JSON 数组。 */
    private String outputModalitiesJson;
    /** 上游明确提供的最大上下文；未知时为空。 */
    private Long contextWindow;
    /** 是否明确支持 SSE 流式输出。 */
    private boolean supportsStreaming;
    /** 是否明确支持工具调用。 */
    private boolean supportsTools;
    /** 是否明确支持结构化输出。 */
    private boolean supportsStructuredOutput;
    /** 根据上游计费类型映射的本地价格单位。 */
    private String priceUnit;
    /** 固定同步来源。 */
    private String syncSource;
    /** 上游模型稳定键。 */
    private String sourceModelKey;
    /** 上游白名单元数据 JSON。 */
    private String sourceMetadataJson;
    /** 上游白名单元数据 SHA-256。 */
    private String sourcePayloadHash;
    /** 本轮完整快照发现时间。 */
    private Instant seenAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPublicName() { return publicName; }
    public void setPublicName(String publicName) { this.publicName = publicName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getCapabilityType() { return capabilityType; }
    public void setCapabilityType(String capabilityType) { this.capabilityType = capabilityType; }
    public String getInputModalitiesJson() { return inputModalitiesJson; }
    public void setInputModalitiesJson(String inputModalitiesJson) { this.inputModalitiesJson = inputModalitiesJson; }
    public String getOutputModalitiesJson() { return outputModalitiesJson; }
    public void setOutputModalitiesJson(String outputModalitiesJson) { this.outputModalitiesJson = outputModalitiesJson; }
    public Long getContextWindow() { return contextWindow; }
    public void setContextWindow(Long contextWindow) { this.contextWindow = contextWindow; }
    public boolean isSupportsStreaming() { return supportsStreaming; }
    public void setSupportsStreaming(boolean supportsStreaming) { this.supportsStreaming = supportsStreaming; }
    public boolean isSupportsTools() { return supportsTools; }
    public void setSupportsTools(boolean supportsTools) { this.supportsTools = supportsTools; }
    public boolean isSupportsStructuredOutput() { return supportsStructuredOutput; }
    public void setSupportsStructuredOutput(boolean supportsStructuredOutput) { this.supportsStructuredOutput = supportsStructuredOutput; }
    public String getPriceUnit() { return priceUnit; }
    public void setPriceUnit(String priceUnit) { this.priceUnit = priceUnit; }
    public String getSyncSource() { return syncSource; }
    public void setSyncSource(String syncSource) { this.syncSource = syncSource; }
    public String getSourceModelKey() { return sourceModelKey; }
    public void setSourceModelKey(String sourceModelKey) { this.sourceModelKey = sourceModelKey; }
    public String getSourceMetadataJson() { return sourceMetadataJson; }
    public void setSourceMetadataJson(String sourceMetadataJson) { this.sourceMetadataJson = sourceMetadataJson; }
    public String getSourcePayloadHash() { return sourcePayloadHash; }
    public void setSourcePayloadHash(String sourcePayloadHash) { this.sourcePayloadHash = sourcePayloadHash; }
    public Instant getSeenAt() { return seenAt; }
    public void setSeenAt(Instant seenAt) { this.seenAt = seenAt; }
}
