package com.nexusapi.server.modules.routing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** MyBatis 计费分组配置行。 */
public class AdminRoutingGroupRow {
    /** 计费与路由分组全局唯一标识。 */
    private UUID id;
    /** 稳定且全局唯一的分组代码，用于配置和策略引用。 */
    private String code;
    /** 管理端展示的分组名称。 */
    private String name;
    /** 分组用途和适用范围说明。 */
    private String description;
    /** 用户最终价格倍率，实际价格等于模型基础售价乘以该值。 */
    private BigDecimal priceMultiplier;
    /** 可见范围：all、assigned 或 internal。 */
    private String audience;
    /** 分组状态：active、disabled 或 degraded。 */
    private String status;
    /** 上游分组归属的商业供应商标识。 */
    private UUID sourceSupplierId;
    /** 上游分组归属的商业供应商名称。 */
    private String sourceSupplierName;
    /** 上游分组原始 ID，用于溯源。 */
    private String sourceGroupId;
    /** 上游最近返回的分组名称。 */
    private String sourceGroupName;
    /** 服务分组同步来源标识。 */
    private String syncSource;
    /** 上游可见状态：active 或 stale。 */
    private String sourceStatus;
    /** 上游成本倍率原始整数，不代表本地售价倍率。 */
    private Long sourceRate;
    /** 上游计费类型原始整数。 */
    private Integer sourceBillingType;
    /** 是否已绑定同步来源身份。 */
    private boolean sourceManaged;
    /** 最近一次在上游完整快照中发现该分组的时间。 */
    private Instant sourceLastSeenAt;
    /** 最近一次上游分组快照变化时间。 */
    private Instant sourceSyncedAt;
    /** 上游快照中当前有效的模型关联数。 */
    private int sourceModelCount;
    /** 上游最后状态为成功的模型关联数。 */
    private int healthyModelCount;
    /** 本轮上游快照已不再返回的历史模型关联数。 */
    private int staleModelCount;
    /** 当前有效且用户状态正常的明确授权人数。 */
    private int authorizedUserCount;
    /** 当前分组是否存在至少一条启用且已安全保存的供应商上游 API Key；不包含凭证内容。 */
    private boolean credentialConfigured;
    /** 分组创建时间。 */
    private Instant createdAt;
    /** 分组最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号，防止并发修改倍率或状态时相互覆盖。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public BigDecimal getPriceMultiplier() { return priceMultiplier; }
    public void setPriceMultiplier(BigDecimal priceMultiplier) { this.priceMultiplier = priceMultiplier; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public UUID getSourceSupplierId() { return sourceSupplierId; }
    public void setSourceSupplierId(UUID sourceSupplierId) { this.sourceSupplierId = sourceSupplierId; }
    public String getSourceSupplierName() { return sourceSupplierName; }
    public void setSourceSupplierName(String sourceSupplierName) { this.sourceSupplierName = sourceSupplierName; }
    public String getSourceGroupId() { return sourceGroupId; }
    public void setSourceGroupId(String sourceGroupId) { this.sourceGroupId = sourceGroupId; }
    public String getSourceGroupName() { return sourceGroupName; }
    public void setSourceGroupName(String sourceGroupName) { this.sourceGroupName = sourceGroupName; }
    public String getSyncSource() { return syncSource; }
    public void setSyncSource(String syncSource) { this.syncSource = syncSource; }
    public String getSourceStatus() { return sourceStatus; }
    public void setSourceStatus(String sourceStatus) { this.sourceStatus = sourceStatus; }
    public Long getSourceRate() { return sourceRate; }
    public void setSourceRate(Long sourceRate) { this.sourceRate = sourceRate; }
    public Integer getSourceBillingType() { return sourceBillingType; }
    public void setSourceBillingType(Integer sourceBillingType) { this.sourceBillingType = sourceBillingType; }
    public boolean isSourceManaged() { return sourceManaged; }
    public void setSourceManaged(boolean sourceManaged) { this.sourceManaged = sourceManaged; }
    public Instant getSourceLastSeenAt() { return sourceLastSeenAt; }
    public void setSourceLastSeenAt(Instant sourceLastSeenAt) { this.sourceLastSeenAt = sourceLastSeenAt; }
    public Instant getSourceSyncedAt() { return sourceSyncedAt; }
    public void setSourceSyncedAt(Instant sourceSyncedAt) { this.sourceSyncedAt = sourceSyncedAt; }
    public int getSourceModelCount() { return sourceModelCount; }
    public void setSourceModelCount(int sourceModelCount) { this.sourceModelCount = sourceModelCount; }
    public int getHealthyModelCount() { return healthyModelCount; }
    public void setHealthyModelCount(int healthyModelCount) { this.healthyModelCount = healthyModelCount; }
    public int getStaleModelCount() { return staleModelCount; }
    public void setStaleModelCount(int staleModelCount) { this.staleModelCount = staleModelCount; }
    public int getAuthorizedUserCount() { return authorizedUserCount; }
    public void setAuthorizedUserCount(int authorizedUserCount) { this.authorizedUserCount = authorizedUserCount; }
    public boolean isCredentialConfigured() { return credentialConfigured; }
    public void setCredentialConfigured(boolean credentialConfigured) { this.credentialConfigured = credentialConfigured; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
