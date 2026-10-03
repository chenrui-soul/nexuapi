package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 一次限时积分发放形成的独立资金批次。 */
public class ExpiringCreditBatchRow {
    /** 批次主键。 */
    private UUID id;
    /** 批次所属用户。 */
    private UUID userId;
    /** 订阅积分关联的订阅记录；通用限时积分为空。 */
    private UUID subscriptionId;
    /** 创建批次的入账流水；升级兼容批次可为空。 */
    private UUID creditLedgerId;
    /** 业务来源类型。 */
    private String sourceType;
    /** 业务来源记录标识。 */
    private String sourceId;
    /** 初始发放积分。 */
    private BigDecimal grantedAmount;
    /** 当前可用积分。 */
    private BigDecimal availableAmount;
    /** 当前冻结积分。 */
    private BigDecimal frozenAmount;
    /** 已结算且未退款的净消费积分。 */
    private BigDecimal consumedAmount;
    /** 已到期失效积分。 */
    private BigDecimal expiredAmount;
    /** 批次到期时间。 */
    private Instant expiresAt;
    /** 批次状态。 */
    private String status;
    /** 批次创建时间。 */
    private Instant createdAt;
    /** 批次最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }
    public UUID getCreditLedgerId() { return creditLedgerId; }
    public void setCreditLedgerId(UUID creditLedgerId) { this.creditLedgerId = creditLedgerId; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public BigDecimal getGrantedAmount() { return grantedAmount; }
    public void setGrantedAmount(BigDecimal grantedAmount) { this.grantedAmount = grantedAmount; }
    public BigDecimal getAvailableAmount() { return availableAmount; }
    public void setAvailableAmount(BigDecimal availableAmount) { this.availableAmount = availableAmount; }
    public BigDecimal getFrozenAmount() { return frozenAmount; }
    public void setFrozenAmount(BigDecimal frozenAmount) { this.frozenAmount = frozenAmount; }
    public BigDecimal getConsumedAmount() { return consumedAmount; }
    public void setConsumedAmount(BigDecimal consumedAmount) { this.consumedAmount = consumedAmount; }
    public BigDecimal getExpiredAmount() { return expiredAmount; }
    public void setExpiredAmount(BigDecimal expiredAmount) { this.expiredAmount = expiredAmount; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}

