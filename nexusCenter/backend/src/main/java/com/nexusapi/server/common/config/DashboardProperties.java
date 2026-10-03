package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Wave 8 经营数据聚合参数，回填范围和周期均收敛到有界值。 */
@ConfigurationProperties(prefix = "nexus.dashboard")
public record DashboardProperties(
        boolean enabled,
        int backfillDays,
        int recentDays,
        int recentHours
) {
    public DashboardProperties {
        backfillDays = bounded(backfillDays, 90, 1, 90);
        recentDays = bounded(recentDays, 2, 1, 7);
        recentHours = bounded(recentHours, 48, 1, 168);
    }

    private static int bounded(int value, int fallback, int minimum, int maximum) {
        int normalized = value <= 0 ? fallback : value;
        return Math.max(minimum, Math.min(normalized, maximum));
    }
}

