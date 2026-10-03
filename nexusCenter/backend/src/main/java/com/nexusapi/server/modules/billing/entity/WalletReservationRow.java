package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 一次 AI 请求的冻结、结算和累计退款状态。 */
public class WalletReservationRow {
    /** 冻结单全局唯一标识。 */
    private UUID id;
    /** 冻结单所属用户标识。 */
    private UUID userId;
    /** 发起请求的 API 令牌标识；系统内部请求可为 null。 */
    private UUID apiKeyId;
    /** 请求实际使用的服务分组；非 Gateway 资金操作可为 null。 */
    private UUID serviceGroupId;
    /** 关联 AI 调用的请求标识，同一用户内唯一。 */
    private String requestId;
    /** 创建冻结单的用户维度幂等键。 */
    private String idempotencyKey;
    /** 冻结单状态：reserved、settled 或 released。 */
    private String status;
    /** 请求开始前预冻结的平台额度总额。 */
    private BigDecimal reservedAmount;
    /** 预冻结金额中来自永久额度的部分。 */
    private BigDecimal reservedPermanent;
    /** 预冻结金额中来自有效期额度的部分。 */
    private BigDecimal reservedExpiring;
    /** 请求完成后按实际用量确认的累计结算额度。 */
    private BigDecimal settledAmount;
    /** 实际结算中消耗永久额度的部分。 */
    private BigDecimal settledPermanent;
    /** 实际结算中消耗有效期额度的部分。 */
    private BigDecimal settledExpiring;
    /** 已针对该冻结单完成的累计退款额度。 */
    private BigDecimal refundedAmount;
    /** 累计退款中退回永久额度的部分。 */
    private BigDecimal refundedPermanent;
    /** 累计退款中退回有效期额度的部分。 */
    private BigDecimal refundedExpiring;
    /** 结算操作的用户维度幂等键；尚未结算时为 null。 */
    private String settlementIdempotencyKey;
    /** 释放冻结操作的用户维度幂等键；尚未释放时为 null。 */
    private String releaseIdempotencyKey;
    /** 冻结单创建时间。 */
    private Instant createdAt;
    /** 完成结算的时间；未结算时为 null。 */
    private Instant settledAt;
    /** 未消费冻结额度被释放的时间；未释放时为 null。 */
    private Instant releasedAt;
    /** 冻结单最后更新时间。 */
    private Instant updatedAt;
    /** 冻结单乐观锁版本号，防止并发结算、释放或退款覆盖。 */
    private long version;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(UUID apiKeyId) { this.apiKeyId = apiKeyId; }
    public UUID getServiceGroupId() { return serviceGroupId; }
    public void setServiceGroupId(UUID serviceGroupId) { this.serviceGroupId = serviceGroupId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public BigDecimal getReservedAmount() { return reservedAmount; }
    public void setReservedAmount(BigDecimal reservedAmount) { this.reservedAmount = reservedAmount; }
    public BigDecimal getReservedPermanent() { return reservedPermanent; }
    public void setReservedPermanent(BigDecimal reservedPermanent) { this.reservedPermanent = reservedPermanent; }
    public BigDecimal getReservedExpiring() { return reservedExpiring; }
    public void setReservedExpiring(BigDecimal reservedExpiring) { this.reservedExpiring = reservedExpiring; }
    public BigDecimal getSettledAmount() { return settledAmount; }
    public void setSettledAmount(BigDecimal settledAmount) { this.settledAmount = settledAmount; }
    public BigDecimal getSettledPermanent() { return settledPermanent; }
    public void setSettledPermanent(BigDecimal settledPermanent) { this.settledPermanent = settledPermanent; }
    public BigDecimal getSettledExpiring() { return settledExpiring; }
    public void setSettledExpiring(BigDecimal settledExpiring) { this.settledExpiring = settledExpiring; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public void setRefundedAmount(BigDecimal refundedAmount) { this.refundedAmount = refundedAmount; }
    public BigDecimal getRefundedPermanent() { return refundedPermanent; }
    public void setRefundedPermanent(BigDecimal refundedPermanent) { this.refundedPermanent = refundedPermanent; }
    public BigDecimal getRefundedExpiring() { return refundedExpiring; }
    public void setRefundedExpiring(BigDecimal refundedExpiring) { this.refundedExpiring = refundedExpiring; }
    public String getSettlementIdempotencyKey() { return settlementIdempotencyKey; }
    public void setSettlementIdempotencyKey(String value) { this.settlementIdempotencyKey = value; }
    public String getReleaseIdempotencyKey() { return releaseIdempotencyKey; }
    public void setReleaseIdempotencyKey(String value) { this.releaseIdempotencyKey = value; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public Instant getReleasedAt() { return releasedAt; }
    public void setReleasedAt(Instant releasedAt) { this.releasedAt = releasedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
}
