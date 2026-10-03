package com.nexusapi.server.modules.billing.provider;

/** 支付下单结果；payUrl 仅用于跳转，不记录私钥或完整签名参数到日志。 */
public record PaymentOrderResult(String providerOrderId, String payUrl) {}
