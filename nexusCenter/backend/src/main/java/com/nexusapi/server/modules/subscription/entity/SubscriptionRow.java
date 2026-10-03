package com.nexusapi.server.modules.subscription.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** subscriptions 与 plans 联查后的当前订阅持久化对象。 */
public class SubscriptionRow extends PlanRow {
    /** 用户订阅记录主键。 */
    private UUID subscriptionId;
    /** 订阅所属用户主键。 */
    private UUID userId;
    /** 当前订阅关联的套餐主键。 */
    private UUID planId;
    /** 当前订阅关联的支付订单主键。 */
    private UUID orderId;
    /** 当前订阅业务状态。 */
    private String subscriptionStatus;
    /** 当前订阅周期开始时间。 */
    private Instant startsAt;
    /** 当前订阅周期结束时间。 */
    private Instant expiresAt;
    /** 是否允许周期结束后自动续订。 */
    private boolean autoRenew;
    /** 用户或系统取消订阅的时间。 */
    private Instant cancelledAt;
    /** 用户发起取消退款的时间。 */
    private Instant refundRequestedAt;
    /** 退款成功或模拟退款完成时间。 */
    private Instant refundCompletedAt;
    /** 当前已确认的退款金额。 */
    private BigDecimal refundAmount;
    /** 当前已确认的可退款订阅积分。 */
    private BigDecimal refundableCredits;
    /** 订阅创建来源，例如 mock 或 admin。 */
    private String source;
    /** 订阅记录乐观锁版本号。 */
    private long subscriptionVersion;

    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public String getSubscriptionStatus() { return subscriptionStatus; }
    public void setSubscriptionStatus(String subscriptionStatus) { this.subscriptionStatus = subscriptionStatus; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public boolean isAutoRenew() { return autoRenew; }
    public void setAutoRenew(boolean autoRenew) { this.autoRenew = autoRenew; }
    public Instant getCancelledAt() { return cancelledAt; }
    public void setCancelledAt(Instant cancelledAt) { this.cancelledAt = cancelledAt; }
    public Instant getRefundRequestedAt() { return refundRequestedAt; }
    public void setRefundRequestedAt(Instant refundRequestedAt) { this.refundRequestedAt = refundRequestedAt; }
    public Instant getRefundCompletedAt() { return refundCompletedAt; }
    public void setRefundCompletedAt(Instant refundCompletedAt) { this.refundCompletedAt = refundCompletedAt; }
    public BigDecimal getRefundAmount() { return refundAmount; }
    public void setRefundAmount(BigDecimal refundAmount) { this.refundAmount = refundAmount; }
    public BigDecimal getRefundableCredits() { return refundableCredits; }
    public void setRefundableCredits(BigDecimal refundableCredits) { this.refundableCredits = refundableCredits; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public long getSubscriptionVersion() { return subscriptionVersion; }
    public void setSubscriptionVersion(long subscriptionVersion) { this.subscriptionVersion = subscriptionVersion; }
}
