package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** 计费引擎灰度开关；默认 v1，shadow 只比较不改变扣费。 */
@ConfigurationProperties(prefix = "nexus.pricing")
public record PricingProperties(
        Mode mode,
        BigDecimal shadowWarningThreshold
) {
    public enum Mode {
        V1,
        SHADOW,
        V2
    }
}
