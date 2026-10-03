package com.nexusapi.server.modules.billing.entity;

import java.math.BigDecimal;
import java.util.UUID;

/** 正式渠道待退款快照；用于事务外调用渠道退款接口。 */
public record PendingPaymentRefundRow(UUID refundId, UUID subscriptionId, UUID userId, UUID orderId,
                                      String provider, String providerOrderId, String orderNo,
                                      BigDecimal amount, String outRequestNo) {}
