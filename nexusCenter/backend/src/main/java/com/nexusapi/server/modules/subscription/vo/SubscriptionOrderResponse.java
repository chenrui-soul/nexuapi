package com.nexusapi.server.modules.subscription.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.UUID;

/** 订阅支付订单响应，不返回任何商户私钥或支付宝公钥。 */
public record SubscriptionOrderResponse(
        UUID id,
        @JsonProperty("subscription_id") UUID subscriptionId,
        @JsonProperty("order_no") String orderNo,
        BigDecimal amount,
        String currency,
        String status,
        @JsonProperty("payment_provider") String paymentProvider,
        @JsonProperty("pay_url") String payUrl
) {}
