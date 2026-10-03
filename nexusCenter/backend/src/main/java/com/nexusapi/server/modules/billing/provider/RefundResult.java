package com.nexusapi.server.modules.billing.provider;

import java.math.BigDecimal;

/** 退款调用或查询结果；支付宝同步返回成功时即可作为渠道确认。 */
public record RefundResult(boolean succeeded, String providerRefundId, BigDecimal amount, String rawCode, String message) {}
