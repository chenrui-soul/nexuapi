package com.nexusapi.server.modules.subscription.entity;

import java.math.BigDecimal;
import java.util.UUID;

/** 取消退款计算所需的订阅、订单和批次快照；不向用户直接暴露。 */
public class SubscriptionRefundContextRow {
    /** 订阅记录主键。 */
    private UUID subscriptionId;
    /** 订阅所属用户主键。 */
    private UUID userId;
    /** 关联的支付订单主键，历史订阅可能为空。 */
    private UUID orderId;
    /** 原订阅实付金额。 */
    private BigDecimal orderAmount;
    /** 原支付订单状态。 */
    private String orderStatus;
    /** 原支付订单渠道；只有 mock 渠道允许在当前阶段自动完成退款。 */
    private String paymentProvider;
    /** 订阅积分发放总额。 */
    private BigDecimal grantedCredits;
    /** 订阅积分最终未使用总额。 */
    private BigDecimal refundableCredits;
    /** 当前在途冻结积分。 */
    private BigDecimal frozenCredits;

    public UUID getSubscriptionId() { return subscriptionId; }
    public void setSubscriptionId(UUID subscriptionId) { this.subscriptionId = subscriptionId; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getOrderId() { return orderId; }
    public void setOrderId(UUID orderId) { this.orderId = orderId; }
    public BigDecimal getOrderAmount() { return orderAmount; }
    public void setOrderAmount(BigDecimal orderAmount) { this.orderAmount = orderAmount; }
    public String getOrderStatus() { return orderStatus; }
    public void setOrderStatus(String orderStatus) { this.orderStatus = orderStatus; }
    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }
    public BigDecimal getGrantedCredits() { return grantedCredits; }
    public void setGrantedCredits(BigDecimal grantedCredits) { this.grantedCredits = grantedCredits; }
    public BigDecimal getRefundableCredits() { return refundableCredits; }
    public void setRefundableCredits(BigDecimal refundableCredits) { this.refundableCredits = refundableCredits; }
    public BigDecimal getFrozenCredits() { return frozenCredits; }
    public void setFrozenCredits(BigDecimal frozenCredits) { this.frozenCredits = frozenCredits; }
}
