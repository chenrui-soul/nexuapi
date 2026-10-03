package com.nexusapi.server.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "nexus")
public record NexusProperties(
        Security security,
        Auth auth,
        ApiKey apiKey,
        SystemAccessToken systemAccessToken,
        Gateway gateway
) {
    public record Security(
            List<String> allowedOrigins,
            String fieldEncryptionKey,
            String lookupHmacKey
    ) {
    }

    public record Auth(
            Duration captchaTtl,
            int captchaMaxAttempts,
            int captchaIssuesPerMinute,
            int loginMaxFailures,
            Duration loginFailureWindow,
            Duration sessionTtl,
            Duration rememberSessionTtl,
            Duration passwordResetTtl,
            int passwordResetMaxAttempts,
            int passwordResetRequestsPerHour,
            boolean passwordResetMailEnabled,
            String passwordResetMailFrom
    ) {
    }

    public record ApiKey(
            int activeHashVersion,
            Map<Integer, String> hmacKeys,
            int maxPerUser,
            int createPerUserPerMinute,
            int createPerIpPerMinute
    ) {
    }

    /** 系统访问令牌使用独立 HMAC 密钥和数量限制，不与用户 API 令牌共享鉴权域。 */
    public record SystemAccessToken(
            int activeHashVersion,
            Map<Integer, String> hmacKeys,
            int maxPerUser
    ) {
    }

    public record Gateway(
            Duration connectTimeout,
            Duration responseTimeout,
            int maxConnections,
            int pendingAcquireMaxCount,
            int maxAttempts,
            long defaultMaxOutputTokens,
            int maxInMemoryBytes,
            Duration quotaKeyTtl,
            Duration textQueueMaxWait
    ) {
    }
}
