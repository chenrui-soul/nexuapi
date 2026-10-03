package com.nexusapi.server.modules.billing.provider;

import java.math.BigDecimal;

/** 已完成验签且经过渠道字段抽取的支付通知。 */
public record PaymentCallback(
        String eventId,
        String outTradeNo,
        String providerOrderId,
        String tradeStatus,
        BigDecimal amount,
        String appId
) {}
