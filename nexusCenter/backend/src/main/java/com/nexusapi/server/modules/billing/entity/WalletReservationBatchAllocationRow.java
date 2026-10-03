package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 一次请求从某个限时积分批次冻结、结算、释放和退款的分摊状态。 */
public class WalletReservationBatchAllocationRow {
    /** 分摊记录主键。 */
    private UUID id;
    /** 关联冻结单。 */
    private UUID reservationId;
    /** 关联限时积分批次。 */
    private UUID batchId;
    /** 上游调用前预冻结积分。 */
    private BigDecimal reservedAmount;
    /** 结算超过预冻结时额外直接结算积分。 */
    private BigDecimal additionalAmount;
    /** 已确认结算积分。 */
    private BigDecimal settledAmount;
    /** 有效期内释放回批次的积分。 */
    private BigDecimal releasedAmount;
    /** 释放时已过期而失效的积分。 */
    private BigDecimal expiredAmount;
    /** 按原来源退回该批次的积分。 */
    private BigDecimal refundedAmount;
    /** 分摊创建时间。 */
    private Instant createdAt;
    /** 分摊最后更新时间。 */
    private Instant updatedAt;
    /** 乐观锁版本号。 */
    private long version;
    /** 联查批次到期时间，决定释放和退款是否仍可用。 */
    private Instant batchExpiresAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public UUID getBatchId() { return batchId; }
    public void setBatchId(UUID batchId) { this.batchId = batchId; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(BigDecimal reservedAmount) { this.reservedAmount = reservedAmount; }
    public BigDecimal getAdditionalAmount() { return additionalAmount; }
    public void setAdditionalAmount(BigDecimal additionalAmount) { this.additionalAmount = additionalAmount; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
    public BigDecimal getReleasedAmount() { return releasedAmount; }
    public void setReleasedAmount(BigDecimal releasedAmount) { this.releasedAmount = releasedAmount; }
    public BigDecimal getExpiredAmount() { return expiredAmount; }
    public void setExpiredAmount(BigDecimal expiredAmount) { this.expiredAmount = expiredAmount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getBatchExpiresAt() { return batchExpiresAt; }
    public void setBatchExpiresAt(Instant batchExpiresAt) { this.batchExpiresAt = batchExpiresAt; }
}

