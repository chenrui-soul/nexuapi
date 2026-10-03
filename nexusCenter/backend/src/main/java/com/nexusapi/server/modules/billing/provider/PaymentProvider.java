package com.nexusapi.server.modules.billing.provider;

import java.math.BigDecimal;
import java.util.Map;

/** 支付渠道最小能力契约；业务层不感知支付宝字段和签名细节。 */
public interface PaymentProvider {
    String code();

    PaymentOrderResult createPayment(String orderNo, BigDecimal amount, String subject, String currency);

    PaymentCallback verifyPaymentCallback(Map<String, String> parameters);

    PaymentQueryResult queryPayment(String outTradeNo);

    RefundResult requestRefund(String providerOrderId, String outTradeNo, BigDecimal amount, String reason, String outRequestNo);

    RefundResult queryRefund(String providerOrderId, String outTradeNo, String outRequestNo);

    /** 渠道若提供退款异步通知，可覆盖此方法；支付宝标准退款不提供该通知。 */
    default RefundCallback verifyRefundCallback(java.util.Map<String, String> parameters) {
        throw new com.nexusapi.server.common.error.BusinessException(
                com.nexusapi.server.common.error.ErrorCode.PAYMENT_CALLBACK_INVALID,
                "当前渠道不支持退款异步回调", null
        );
    }
}
