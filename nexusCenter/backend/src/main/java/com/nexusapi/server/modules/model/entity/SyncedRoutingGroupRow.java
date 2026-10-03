package com.nexusapi.server.modules.model.entity;

import java.time.Instant;
import java.util.UUID;

/** 已通过白名单和结构校验的上游服务分组快照。 */
public class SyncedRoutingGroupRow {
    /** 新建本地服务分组时使用的全局唯一标识。 */
    private UUID id;
    /** 上游分组原始 ID，原样保留用于溯源。 */
    private String sourceGroupId;
    /** 上游分组原始名称。 */
    private String sourceGroupName;
    /** 新建本地分组时使用的稳定技术编码。 */
    private String localCode;
    /** 上游返回的成本倍率原始整数，不代表本地售价倍率。 */
    private long sourceRate;
    /** 上游返回的计费类型原始整数。 */
    private int sourceBillingType;
    /** 上游分组的脱敏白名单 JSON 快照。 */
    private String sourceMetadataJson;
    /** 白名单快照 SHA-256，用于避免无意义更新。 */
    private String sourcePayloadHash;
    /** 本轮完整快照发现该分组的时间。 */
    private Instant seenAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getSourceGroupId() { return sourceGroupId; }
    public void setSourceGroupId(String sourceGroupId) { this.sourceGroupId = sourceGroupId; }
    public String getSourceGroupName() { return sourceGroupName; }
    public void setSourceGroupName(String sourceGroupName) { this.sourceGroupName = sourceGroupName; }
    public String getLocalCode() { return localCode; }
    public void setLocalCode(String localCode) { this.localCode = localCode; }
    public long getSourceRate() { return sourceRate; }
    public void setSourceRate(long sourceRate) { this.sourceRate = sourceRate; }
    public int getSourceBillingType() { return sourceBillingType; }
    public void setSourceBillingType(int sourceBillingType) { this.sourceBillingType = sourceBillingType; }
    public String getSourceMetadataJson() { return sourceMetadataJson; }
    public void setSourceMetadataJson(String sourceMetadataJson) { this.sourceMetadataJson = sourceMetadataJson; }
    public String getSourcePayloadHash() { return sourcePayloadHash; }
    public void setSourcePayloadHash(String sourcePayloadHash) { this.sourcePayloadHash = sourcePayloadHash; }
    public Instant getSeenAt() { return seenAt; }
    public void setSeenAt(Instant seenAt) { this.seenAt = seenAt; }
}
