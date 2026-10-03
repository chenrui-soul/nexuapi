package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.util.UUID;

/** 支付回调所需的最小订单快照。 */
public class PaymentOrderRow {
    /** 平台订单标识。 */ private UUID id;
    /** 订单所属用户。 */ private UUID userId;
    /** 平台订单号。 */ private String orderNo;
    /** recharge 或 subscription。 */ private String orderType;
    /** 应付金额，回调必须精确一致。 */ private BigDecimal amount;
    /** 订单币种。 */ private String currency;
    /** 平台订单状态。 */ private String status;
    /** 支付渠道。 */ private String paymentProvider;
    /** 渠道交易号。 */ private String providerOrderId;
    /** 非敏感业务快照。 */ private String metadataJson;
    public UUID getId() { return id; } public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; } public void setUserId(UUID userId) { this.userId = userId; }
    public String getOrderNo() { return orderNo; } public void setOrderNo(String orderNo) { this.orderNo = orderNo; }
    public String getOrderType() { return orderType; } public void setOrderType(String orderType) { this.orderType = orderType; }
    public BigDecimal getAmount() { return amount; } public void setAmount(BigDecimal amount) { this.amount = amount; }
    public String getCurrency() { return currency; } public void setCurrency(String currency) { this.currency = currency; }
    public String getStatus() { return status; } public void setStatus(String status) { this.status = status; }
    public String getPaymentProvider() { return paymentProvider; } public void setPaymentProvider(String paymentProvider) { this.paymentProvider = paymentProvider; }
    public String getProviderOrderId() { return providerOrderId; } public void setProviderOrderId(String providerOrderId) { this.providerOrderId = providerOrderId; }
    public String getMetadataJson() { return metadataJson; } public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }
}
