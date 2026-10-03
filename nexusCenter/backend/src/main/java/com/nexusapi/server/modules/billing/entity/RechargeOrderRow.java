package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** 充值订单持久化行，记录订单状态及支付渠道关联信息。 */
public class RechargeOrderRow {
    /** 充值订单内部唯一标识。 */
    private UUID id;
    /** 订单所属用户标识。 */
    private UUID userId;
    /** 对外展示的充值订单号。 */
    private String orderNo;
    /** 用户支付的法定货币金额。 */
    private BigDecimal amount;
    /** 订单金额币种，例如 CNY。 */
    private String currency;
    /** 订单状态，例如 pending、paid、closed。 */
    private String status;
    /** 支付渠道标识，例如 mock、alipay、wechat。 */
    private String paymentProvider;
    /** 支付渠道侧订单号。 */
    private String providerOrderId;
    /** 用户侧幂等键，保证重复提交不会生成重复订单。 */
    private String idempotencyKey;
    /** 订单完成支付的时间。 */
    private Instant paidAt;
    /** 订单关闭时间。 */
    private Instant closedAt;
    /** 订单扩展元数据 JSON。 */
    private String metadataJson;
    /** 订单创建时间。 */
    private Instant createdAt;
    /** 订单最后更新时间。 */
    private Instant updatedAt;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getOrderNo() { return orderNo; }
    public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPaymentProvider() { return paymentProvider; }
    public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }
    public String getProviderOrderId() { return providerOrderId; }
    public void setProviderOrderId(String providerOrderId) { this.providerOrderId = providerOrderId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
