package com.nexusapi.server.modules.model.entity;

import java.time.Instant;
import java.util.UUID;

/** 同步任务批量查询到的本地模型最小写入目标。 */
public class ModelSyncTargetRow {
    /** 本地模型全局唯一标识。 */
    private UUID id;
    /** 本地公开模型名称。 */
    private String publicName;
    /** 当前同步来源。 */
    private String syncSource;
    /** 当前上游模型键。 */
    private String sourceModelKey;
    /** 是否允许同步任务更新基础能力字段。 */
    private boolean sourceManaged;
    /** 最近保存的上游白名单快照哈希。 */
    private String sourcePayloadHash;
    /** 当前生效的平台销售价格版本。 */
    private UUID activePricingVersionId;
    /** 是否允许同步任务自动创建并激活上游价格版本。 */
    private boolean pricingSourceManaged;
    /** 最近激活的上游价格快照哈希。 */
    private String pricingSourceHash;
    /** 最近激活上游价格版本的时间。 */
    private Instant pricingSourceSyncedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getPublicName() { return publicName; }
    public void setPublicName(String publicName) { this.publicName = publicName; }
    public String getSyncSource() { return syncSource; }
    public void setSyncSource(String syncSource) { this.syncSource = syncSource; }
    public String getSourceModelKey() { return sourceModelKey; }
    public void setSourceModelKey(String sourceModelKey) { this.sourceModelKey = sourceModelKey; }
    public boolean isSourceManaged() { return sourceManaged; }
    public void setSourceManaged(boolean sourceManaged) { this.sourceManaged = sourceManaged; }
    public String getSourcePayloadHash() { return sourcePayloadHash; }
    public void setSourcePayloadHash(String sourcePayloadHash) { this.sourcePayloadHash = sourcePayloadHash; }
    public UUID getActivePricingVersionId() { return activePricingVersionId; }
    public void setActivePricingVersionId(UUID activePricingVersionId) { this.activePricingVersionId = activePricingVersionId; }
    public boolean isPricingSourceManaged() { return pricingSourceManaged; }
    public void setPricingSourceManaged(boolean pricingSourceManaged) { this.pricingSourceManaged = pricingSourceManaged; }
    public String getPricingSourceHash() { return pricingSourceHash; }
    public void setPricingSourceHash(String pricingSourceHash) { this.pricingSourceHash = pricingSourceHash; }
    public Instant getPricingSourceSyncedAt() { return pricingSourceSyncedAt; }
    public void setPricingSourceSyncedAt(Instant pricingSourceSyncedAt) { this.pricingSourceSyncedAt = pricingSourceSyncedAt; }
}
