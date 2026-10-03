package com.nexusapi.server.modules.billing.provider;

import java.math.BigDecimal;

/** 已验签的异步退款通知；支付宝标准退款当前不产生此通知。 */
public record RefundCallback(String eventId, String providerRefundId, String outRequestNo,
                             BigDecimal amount, boolean succeeded) {}
