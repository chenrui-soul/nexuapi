package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 每一次部分或全额退款的幂等记录。 */
public class BillingRefundRow {
    /** 退款记录全局唯一标识。 */
    private UUID id;
    /** 退款所属用户标识。 */
    private UUID userId;
    /** 被退款的原冻结单标识。 */
    private UUID reservationId;
    /** 用户维度唯一的退款幂等键。 */
    private String idempotencyKey;
    /** 本次退款的平台额度总额。 */
    private BigDecimal amount;
    /** 本次退回永久额度的金额。 */
    private BigDecimal permanentAmount;
    /** 本次退回有效期额度的金额。 */
    private BigDecimal expiringAmount;
    /** 本次退款完成后该冻结单的累计退款总额。 */
    private BigDecimal cumulativeAmount;
    /** 本次退款完成后该冻结单仍可退款的额度。 */
    private BigDecimal refundableAfter;
    /** 退款原因说明，不得包含密码、Token、密钥等敏感信息。 */
    private String reason;
    /** 退款记录创建时间。 */
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getPermanentAmount() { return permanentAmount; }
    public void setPermanentAmount(BigDecimal permanentAmount) { this.permanentAmount = permanentAmount; }
    public BigDecimal getExpiringAmount() { return expiringAmount; }
    public void setExpiringAmount(BigDecimal expiringAmount) { this.expiringAmount = expiringAmount; }
    public BigDecimal getCumulativeAmount() { return cumulativeAmount; }
    public void setCumulativeAmount(BigDecimal cumulativeAmount) { this.cumulativeAmount = cumulativeAmount; }
    public BigDecimal getRefundableAfter() { return refundableAfter; }
    public void setRefundableAfter(BigDecimal refundableAfter) { this.refundableAfter = refundableAfter; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
