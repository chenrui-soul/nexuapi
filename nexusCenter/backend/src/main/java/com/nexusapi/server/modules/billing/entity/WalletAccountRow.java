package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 钱包当前余额快照，写操作必须在行锁内更新。 */
public class WalletAccountRow {
    /** 钱包所属用户标识，同时也是钱包账户主键。 */
    private UUID userId;
    /** 永久有效的平台额度余额，不参与自动过期。 */
    private BigDecimal permanentCredits;
    /** 带有效期的平台额度汇总余额，由到期任务按明细扣减。 */
    private BigDecimal expiringCredits;
    /** 已为在途请求冻结、暂不可再次消费的平台额度。 */
    private BigDecimal frozenCredits;
    /** 钱包乐观锁版本号，每次余额变更后递增。 */
    private long version;
    /** 钱包余额快照最后更新时间。 */
    private Instant updatedAt;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public BigDecimal getPermanentCredits() { return permanentCredits; }
    public void setPermanentCredits(BigDecimal permanentCredits) { this.permanentCredits = permanentCredits; }
    public BigDecimal getExpiringCredits() { return expiringCredits; }
    public void setExpiringCredits(BigDecimal expiringCredits) { this.expiringCredits = expiringCredits; }
    public BigDecimal getFrozenCredits() { return frozenCredits; }
    public void setFrozenCredits(BigDecimal frozenCredits) { this.frozenCredits = frozenCredits; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
