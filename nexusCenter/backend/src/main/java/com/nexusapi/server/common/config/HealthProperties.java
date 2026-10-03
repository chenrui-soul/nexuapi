package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 渠道健康探测与告警参数，所有时长和批量上限都在启动时收敛到安全范围。 */
@ConfigurationProperties(prefix = "nexus.health")
public record HealthProperties(
        boolean enabled,
        Duration probeInterval,
        Duration initialDelay,
        Duration probeTimeout,
        Duration alertCooldown,
        Duration lockTtl,
        int batchSize
) {
    public HealthProperties {
        probeInterval = positiveOrDefault(probeInterval, Duration.ofMinutes(1));
        initialDelay = nonNegativeOrDefault(initialDelay, Duration.ofSeconds(15));
        probeTimeout = positiveOrDefault(probeTimeout, Duration.ofSeconds(5));
        alertCooldown = positiveOrDefault(alertCooldown, Duration.ofMinutes(30));
        batchSize = batchSize <= 0 ? 20 : Math.min(batchSize, 200);
        Duration minimumLockTtl = probeTimeout.multipliedBy(batchSize).plusSeconds(15);
        lockTtl = positiveOrDefault(lockTtl, Duration.ofMinutes(3));
        if (lockTtl.compareTo(minimumLockTtl) < 0) {
            // 锁必须覆盖最坏情况下整批串行探测，避免另一实例重复探测并写入重复观测记录。
            lockTtl = minimumLockTtl;
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static Duration nonNegativeOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() ? fallback : value;
    }
}
