package com.nexusapi.server.modules.subscription.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/** 正式购买订阅的下单参数，金额始终读取服务端套餐快照。 */
public record SubscriptionOrderCreateRequest(
        @NotBlank(message = "支付渠道不能为空")
        @JsonProperty("payment_provider") String paymentProvider
) {}
