package com.nexusapi.server.modules.billing.provider;

import java.math.BigDecimal;

/** 渠道主动查询支付订单的结果，用于通知丢失后的对账补偿。 */
public record PaymentQueryResult(boolean paid, String providerOrderId, String outTradeNo,
                                 BigDecimal amount, String tradeStatus, String rawCode) {}
