package com.nexusapi.server.modules.billing.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;

public record RechargeOrderCreateRequest(
        @DecimalMin(value = "10.00", message = "充值金额最低 10 元")
        @Digits(integer = 12, fraction = 2, message = "充值金额最多保留 2 位小数")
        BigDecimal amount,
        @NotBlank(message = "支付渠道不能为空")
        @JsonProperty("payment_provider") String paymentProvider
) {
}
