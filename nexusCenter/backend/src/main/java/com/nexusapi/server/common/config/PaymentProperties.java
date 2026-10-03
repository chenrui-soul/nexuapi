package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "nexus.payment")
public record PaymentProperties(
        boolean mockEnabled,
        BigDecimal pointsPerCurrency,
        Alipay alipay
) {
    public PaymentProperties {
        pointsPerCurrency = pointsPerCurrency == null ? new BigDecimal("0.5") : pointsPerCurrency;
        alipay = alipay == null ? new Alipay(false, "", "", "", "", "https://openapi.alipay.com/gateway.do", "", "", "RSA2") : alipay;
    }

    /** 支付宝官方开放平台配置；私钥和公钥仅从部署环境注入，禁止写入数据库或接口响应。 */
    public record Alipay(
            boolean enabled,
            String appId,
            String privateKey,
            String alipayPublicKey,
            String sellerId,
            String gateway,
            String notifyUrl,
            String returnUrl,
            String signType
    ) {}
}
