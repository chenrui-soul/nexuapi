package com.nexusapi.server.modules.supplier.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 供应商详情页的平台模型、渠道和上游模型映射行。 */
public class AdminSupplierModelDetailRow {
    /** 渠道模型映射标识。 */
    private UUID mappingId;
    /** 平台模型标识。 */
    private UUID modelId;
    /** 客户端调用时使用的公开模型名。 */
    private String publicName;
    /** 管理端模型显示名。 */
    private String displayName;
    /** 供应商渠道实际接收的上游模型名。 */
    private String upstreamModel;
    /** 所属渠道标识。 */
    private UUID channelId;
    /** 所属渠道名称。 */
    private String channelName;
    /** 上游普通输入 Token 成本价。 */
    private BigDecimal costInputPrice;
    /** 上游缓存输入 Token 成本价。 */
    private BigDecimal costCachedInputPrice;
    /** 上游输出 Token 成本价。 */
    private BigDecimal costOutputPrice;
    /** 渠道模型映射状态。 */
    private String status;
    /** 映射最后更新时间。 */
    private Instant updatedAt;

    public UUID getMappingId() { return mappingId; }
    public void setMappingId(UUID mappingId) { this.mappingId = mappingId; }
    public UUID getModelId() { return modelId; }
    public void setModelId(UUID modelId) { this.modelId = modelId; }
    public String getPublicName() { return publicName; }
    public void setPublicName(String publicName) { this.publicName = publicName; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }
    public String getUpstreamModel() { return upstreamModel; }
    public void setUpstreamModel(String upstreamModel) { this.upstreamModel = upstreamModel; }
    public UUID getChannelId() { return channelId; }
    public void setChannelId(UUID channelId) { this.channelId = channelId; }
    public String getChannelName() { return channelName; }
    public void setChannelName(String channelName) { this.channelName = channelName; }
    public BigDecimal getCostInputPrice() { return costInputPrice; }
    public void setCostInputPrice(BigDecimal costInputPrice) { this.costInputPrice = costInputPrice; }
    public BigDecimal getCostCachedInputPrice() { return costCachedInputPrice; }
    public void setCostCachedInputPrice(BigDecimal value) { this.costCachedInputPrice = value; }
    public BigDecimal getCostOutputPrice() { return costOutputPrice; }
    public void setCostOutputPrice(BigDecimal costOutputPrice) { this.costOutputPrice = costOutputPrice; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
