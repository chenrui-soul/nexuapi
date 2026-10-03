package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 不可变账本行，同时保存分桶变化和操作后快照。 */
public class BillingLedgerRow {
    /** 账本流水全局唯一标识。 */
    private UUID id;
    /** 账本所属用户标识，幂等键也按用户维度隔离。 */
    private UUID userId;
    /** 关联 API 令牌标识；非 API 令牌消费类流水可为 null。 */
    private UUID apiKeyId;
    /** 关联冻结单标识；充值、赠送等非请求流水可为 null。 */
    private UUID reservationId;
    /** 贯穿网关、计费和调用日志的请求标识。 */
    private String requestId;
    /** 流水类型：recharge、grant、reserve、settle、release、refund、expire 或 adjustment。 */
    private String entryType;
    /** 本次永久、有效期和冻结三个分桶变化量之和，可正、可负或为零。 */
    private BigDecimal amount;
    /** 操作后总资产快照，等于永久额度、有效期额度与冻结额度之和。 */
    private BigDecimal balanceAfter;
    /** 用户维度唯一的幂等键，用于避免重复入账或扣费。 */
    private String idempotencyKey;
    /** 业务来源类型，例如 order、request、admin 或 subscription。 */
    private String sourceType;
    /** 业务来源记录标识，便于审计追溯。 */
    private String sourceId;
    /** 本笔额度的到期时间；永久额度或无到期语义时为 null。 */
    private Instant expiresAt;
    /** 非敏感业务元数据 JSON，禁止保存密码、Token、密钥或完整凭证。 */
    private String metadataJson;
    /** 本次永久额度变化量。 */
    private BigDecimal permanentDelta;
    /** 本次有效期额度变化量。 */
    private BigDecimal expiringDelta;
    /** 本次冻结额度变化量。 */
    private BigDecimal frozenDelta;
    /** 操作后的永久额度余额。 */
    private BigDecimal permanentAfter;
    /** 操作后的有效期额度余额。 */
    private BigDecimal expiringAfter;
    /** 操作后的冻结额度余额。 */
    private BigDecimal frozenAfter;
    /** 操作后的可用余额，等于永久额度与有效期额度之和。 */
    private BigDecimal availableAfter;
    /** 生成本流水后对应的钱包版本号。 */
    private long walletVersionAfter;
    /** 账本流水创建时间；账本只允许追加，创建后不可修改或删除。 */
    private Instant createdAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public void setApiKeyId(UUID apiKeyId) { this.apiKeyId = apiKeyId; }
    public UUID getReservationId() { return reservationId; }
    public void setReservationId(UUID reservationId) { this.reservationId = reservationId; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }
    public String getEntryType() { return entryType; }
    public void setEntryType(String entryType) { this.entryType = entryType; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public BigDecimal getPermanentDelta() { return permanentDelta; }
    public void setPermanentDelta(BigDecimal permanentDelta) { this.permanentDelta = permanentDelta; }
    public BigDecimal getExpiringDelta() { return expiringDelta; }
    public void setExpiringDelta(BigDecimal expiringDelta) { this.expiringDelta = expiringDelta; }
    public BigDecimal getFrozenDelta() { return frozenDelta; }
    public void setFrozenDelta(BigDecimal frozenDelta) { this.frozenDelta = frozenDelta; }
    public BigDecimal getPermanentAfter() { return permanentAfter; }
    public void setPermanentAfter(BigDecimal permanentAfter) { this.permanentAfter = permanentAfter; }
    public BigDecimal getExpiringAfter() { return expiringAfter; }
    public void setExpiringAfter(BigDecimal expiringAfter) { this.expiringAfter = expiringAfter; }
    public BigDecimal getFrozenAfter() { return frozenAfter; }
    public void setFrozenAfter(BigDecimal frozenAfter) { this.frozenAfter = frozenAfter; }
    public BigDecimal getAvailableAfter() { return availableAfter; }
    public void setAvailableAfter(BigDecimal availableAfter) { this.availableAfter = availableAfter; }
    public long getWalletVersionAfter() { return walletVersionAfter; }
    public void setWalletVersionAfter(long walletVersionAfter) { this.walletVersionAfter = walletVersionAfter; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
