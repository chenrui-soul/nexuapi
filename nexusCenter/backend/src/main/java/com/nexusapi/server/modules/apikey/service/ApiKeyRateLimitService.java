package com.nexusapi.server.modules.apikey.service;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.crypto.HmacLookupHasher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 创建 API 令牌的 Redis 限频器，按用户和来源 IP 两个维度共同限制。 */
@Service
public class ApiKeyRateLimitService {
    private final StringRedisTemplate redis;
    private final HmacLookupHasher hasher;
    private final NexusProperties.ApiKey properties;

    public ApiKeyRateLimitService(
            StringRedisTemplate redis,
            HmacLookupHasher hasher,
            NexusProperties nexusProperties
    ) {
        this.redis = redis;
        this.hasher = hasher;
        this.properties = nexusProperties.apiKey();
    }

    public void assertCreateAllowed(UUID userId, String ipAddress) {
        long minute = System.currentTimeMillis() / 60_000L;
        long userCount = increment("nexus:apikey:create:user:" + minute + ':' + userId);

        // Redis 键只保存 IP 的用途隔离 HMAC，避免缓存泄露时直接暴露用户来源地址。
        String ipHash = hasher.hashHex("api-key-create-ip", ipAddress);
        long ipCount = increment("nexus:apikey:create:ip:" + minute + ':' + ipHash);
        if (userCount > properties.createPerUserPerMinute()
                || ipCount > properties.createPerIpPerMinute()) {
            throw new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "API 令牌创建过于频繁，请稍后再试", null);
        }
    }

    private long increment(String key) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 窗口键保留两分钟，覆盖分钟边界和短暂时钟偏差，之后由 Redis 自动清理。
            redis.expire(key, 2, TimeUnit.MINUTES);
        }
        return count == null ? 0L : count;
    }
}
