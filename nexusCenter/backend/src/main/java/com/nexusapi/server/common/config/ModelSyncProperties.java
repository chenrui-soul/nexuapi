package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/** 模型市场外部接口和调度保护参数；接口地址只能由可信服务端配置提供。 */
@ConfigurationProperties(prefix = "nexus.model-sync")
public record ModelSyncProperties(
        URI marketUrl,
        URI priceGroupsUrl,
        String priceGroupToken,
        String sourceSupplierCode,
        Duration requestTimeout,
        Duration lockTtl,
        int pageSize,
        int maxModels,
        int maxRetries
) {
    public ModelSyncProperties {
        marketUrl = marketUrl == null
                ? URI.create("https://caicaiapi.cloud/api/models/market")
                : marketUrl;
        priceGroupsUrl = priceGroupsUrl == null
                ? URI.create("https://caicaiapi.cloud/api/price-groups/visible")
                : priceGroupsUrl;
        priceGroupToken = priceGroupToken == null ? "" : priceGroupToken.strip();
        sourceSupplierCode = sourceSupplierCode == null || sourceSupplierCode.isBlank()
                ? "caicai"
                : sourceSupplierCode.strip().toLowerCase(java.util.Locale.ROOT);
        requestTimeout = positiveOrDefault(requestTimeout, Duration.ofSeconds(15));
        lockTtl = positiveOrDefault(lockTtl, Duration.ofMinutes(10));
        pageSize = bounded(pageSize, 100, 1, 100);
        maxModels = bounded(maxModels, 5_000, 1, 10_000);
        maxRetries = bounded(maxRetries, 1, 0, 2);
        validateHttpUrl(marketUrl, "market-url");
        validateHttpUrl(priceGroupsUrl, "price-groups-url");
        if (!sourceSupplierCode.matches("^[a-z][a-z0-9_-]{1,63}$")) {
            throw new IllegalArgumentException("nexus.model-sync.source-supplier-code format is invalid");
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static int bounded(int value, int fallback, int minimum, int maximum) {
        int normalized = value < minimum ? fallback : value;
        return Math.max(minimum, Math.min(normalized, maximum));
    }

    private static void validateHttpUrl(URI uri, String property) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
            throw new IllegalArgumentException("nexus.model-sync." + property + " must use http or https");
        }
    }
}
