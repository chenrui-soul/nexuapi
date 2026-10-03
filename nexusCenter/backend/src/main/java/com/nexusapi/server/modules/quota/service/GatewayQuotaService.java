package com.nexusapi.server.modules.quota.service;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.gateway.security.GatewayCallerPrincipal;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Gateway 的 Redis 原子限流器，统一处理 API 令牌 RPM、TPM、并发和渠道并发。
 *
 * <p>Redis 不可用时 fail closed，不能在依赖故障时绕过用户配置的消费和并发边界。</p>
 */
@Service
public class GatewayQuotaService {
    private static final long ALLOWED = 1L;
    private static final long RPM_REJECTED = -1L;
    private static final long TPM_REJECTED = -2L;
    private static final long CONCURRENCY_REJECTED = -3L;

    private static final DefaultRedisScript<Long> ACQUIRE_API_KEY = new DefaultRedisScript<>("""
            local rpmLimit = tonumber(ARGV[1])
            local tpmLimit = tonumber(ARGV[2])
            local apiConcurrencyLimit = tonumber(ARGV[3])
            local subscriptionConcurrencyLimit = tonumber(ARGV[4])
            local reservedTokens = tonumber(ARGV[5])
            local ttl = tonumber(ARGV[6])

            local rpm = tonumber(redis.call('GET', KEYS[1]) or '0')
            local tpm = tonumber(redis.call('GET', KEYS[2]) or '0')
            local apiConcurrency = tonumber(redis.call('GET', KEYS[3]) or '0')
            local subscriptionConcurrency = tonumber(redis.call('GET', KEYS[4]) or '0')
            if rpmLimit > 0 and rpm + 1 > rpmLimit then return -1 end
            if tpmLimit > 0 and tpm + reservedTokens > tpmLimit then return -2 end
            if apiConcurrencyLimit > 0 and apiConcurrency + 1 > apiConcurrencyLimit then return -3 end
            if subscriptionConcurrencyLimit > 0 and subscriptionConcurrency + 1 > subscriptionConcurrencyLimit then return -3 end

            if rpmLimit > 0 then
              local value = redis.call('INCR', KEYS[1])
              if value == 1 then redis.call('EXPIRE', KEYS[1], ttl) end
            end
            if tpmLimit > 0 then
              local value = redis.call('INCRBY', KEYS[2], reservedTokens)
              if value == reservedTokens then redis.call('EXPIRE', KEYS[2], ttl) end
            end
            if apiConcurrencyLimit > 0 then
              local value = redis.call('INCR', KEYS[3])
              if value == 1 then redis.call('EXPIRE', KEYS[3], ttl) end
            end
            if subscriptionConcurrencyLimit > 0 then
              local value = redis.call('INCR', KEYS[4])
              if value == 1 then redis.call('EXPIRE', KEYS[4], ttl) end
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_API_KEY = new DefaultRedisScript<>("""
            local tpmEnabled = tonumber(ARGV[1])
            local apiConcurrencyEnabled = tonumber(ARGV[2])
            local subscriptionConcurrencyEnabled = tonumber(ARGV[3])
            local tokenDelta = tonumber(ARGV[4])
            local ttl = tonumber(ARGV[5])
            if tpmEnabled == 1 then
              local current = tonumber(redis.call('GET', KEYS[1]) or '0')
              local adjusted = current + tokenDelta
              if adjusted < 0 then adjusted = 0 end
              redis.call('SET', KEYS[1], adjusted, 'EX', ttl)
            end
            if apiConcurrencyEnabled == 1 then
              local current = tonumber(redis.call('GET', KEYS[2]) or '0')
              if current <= 1 then redis.call('DEL', KEYS[2]) else redis.call('DECR', KEYS[2]) end
            end
            if subscriptionConcurrencyEnabled == 1 then
              local current = tonumber(redis.call('GET', KEYS[3]) or '0')
              if current <= 1 then redis.call('DEL', KEYS[3]) else redis.call('DECR', KEYS[3]) end
            end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> ACQUIRE_CHANNEL = new DefaultRedisScript<>("""
            local limit = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            if limit <= 0 then return 1 end
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            if current + 1 > limit then return -3 end
            local value = redis.call('INCR', KEYS[1])
            if value == 1 then redis.call('EXPIRE', KEYS[1], ttl) end
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RELEASE_CHANNEL = new DefaultRedisScript<>("""
            local enabled = tonumber(ARGV[1])
            if enabled == 0 then return 1 end
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            if current <= 1 then redis.call('DEL', KEYS[1]) else redis.call('DECR', KEYS[1]) end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final long keyTtlSeconds;

    public GatewayQuotaService(StringRedisTemplate redis, NexusProperties properties) {
        this.redis = redis;
        this.keyTtlSeconds = Math.max(60L, properties.gateway().quotaKeyTtl().toSeconds());
    }

    /** RPM 按自然分钟计数；TPM 先按安全估算占位，完成后再修正为实际 Token。 */
    public ApiKeyLease acquireApiKey(
            GatewayCallerPrincipal principal,
            long reservedTokens,
            boolean countConcurrency
    ) {
        return acquireApiKey(principal, reservedTokens, countConcurrency, null, null);
    }

    /**
     * 原子申请 API 令牌与订阅共享并发租约；两个限制任一达到上限都会整体拒绝，避免半成功租约。
     */
    public ApiKeyLease acquireApiKey(
            GatewayCallerPrincipal principal,
            long reservedTokens,
            boolean countConcurrency,
            UUID subscriptionId,
            Integer subscriptionConcurrencyLimit
    ) {
        long minute = Instant.now().getEpochSecond() / 60L;
        String identity = principal.apiKeyId() == null
                ? "console:" + principal.userId() : "api-key:" + principal.apiKeyId();
        String prefix = "nexus:gateway:key:" + identity;
        String rpmKey = prefix + ":rpm:" + minute;
        String tpmKey = prefix + ":tpm:" + minute;
        String concurrencyKey = prefix + ":concurrency";
        long rpmLimit = principal.rpmLimit() == null ? 0L : principal.rpmLimit();
        long tpmLimit = principal.tpmLimit() == null ? 0L : principal.tpmLimit();
        long concurrencyLimit = !countConcurrency || principal.concurrencyLimit() == null
                ? 0L : principal.concurrencyLimit();
        long subscriptionLimit = !countConcurrency || subscriptionId == null || subscriptionConcurrencyLimit == null
                ? 0L : subscriptionConcurrencyLimit;
        long safeReservedTokens = Math.max(0L, reservedTokens);
        String subscriptionConcurrencyKey = subscriptionId == null
                ? prefix + ":subscription-disabled"
                : "nexus:gateway:subscription:" + subscriptionId + ":concurrency";
        Long result = execute(
                ACQUIRE_API_KEY,
                List.of(rpmKey, tpmKey, concurrencyKey, subscriptionConcurrencyKey),
                rpmLimit, tpmLimit, concurrencyLimit, subscriptionLimit, safeReservedTokens, keyTtlSeconds
        );
        if (result == null || result != ALLOWED) {
            throw rejection(result);
        }
        return new ApiKeyLease(
                tpmKey,
                concurrencyKey,
                subscriptionConcurrencyKey,
                safeReservedTokens,
                tpmLimit > 0,
                concurrencyLimit > 0,
                subscriptionLimit > 0
        );
    }

    /** 成功时把 TPM 预占修正为实际值；失败时传 0 可释放本次 TPM 占位。 */
    public void releaseApiKey(ApiKeyLease lease, long actualTokens) {
        if (lease == null) {
            return;
        }
        long delta = Math.max(0L, actualTokens) - lease.reservedTokens();
        execute(
                RELEASE_API_KEY,
                List.of(lease.tpmKey(), lease.concurrencyKey(), lease.subscriptionConcurrencyKey()),
                lease.tpmEnabled() ? 1 : 0,
                lease.concurrencyEnabled() ? 1 : 0,
                lease.subscriptionConcurrencyEnabled() ? 1 : 0,
                delta,
                keyTtlSeconds
        );
    }

    public ChannelLease acquireChannel(UUID channelId, Integer configuredLimit) {
        long limit = configuredLimit == null ? 0L : configuredLimit;
        String key = "nexus:gateway:channel:" + channelId + ":concurrency";
        Long result = execute(ACQUIRE_CHANNEL, List.of(key), limit, keyTtlSeconds);
        if (result == null || result != ALLOWED) {
            throw rejection(result);
        }
        return new ChannelLease(key, limit > 0);
    }

    /**
     * 文本流量可以在渠道并发已满时短暂排队，避免把可恢复的削峰场景直接返回为 429。
     * 每次尝试仍通过 Redis Lua 原子占位，因此多应用实例之间也不会超出渠道上限。
     */
    public ChannelLease acquireChannelQueued(
            UUID channelId,
            Integer configuredLimit,
            Duration maxWait
    ) {
        long waitNanos = maxWait == null ? 0L : Math.max(0L, maxWait.toNanos());
        long deadline = System.nanoTime() + waitNanos;
        while (true) {
            try {
                return acquireChannel(channelId, configuredLimit);
            } catch (BusinessException exception) {
                if (exception.errorCode() != ErrorCode.RATE_LIMIT_EXCEEDED
                        || waitNanos == 0L || System.nanoTime() >= deadline) {
                    throw exception;
                }
                long remainingMillis = Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L);
                try {
                    Thread.sleep(Math.min(100L, remainingMillis));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw exception;
                }
            }
        }
    }

    public void releaseChannel(ChannelLease lease) {
        if (lease == null) {
            return;
        }
        execute(RELEASE_CHANNEL, List.of(lease.key()), lease.enabled() ? 1 : 0);
    }

    private Long execute(DefaultRedisScript<Long> script, List<String> keys, Object... arguments) {
        try {
            // StringRedisTemplate 的参数序列化器只接受 String；统一转成十进制文本，
            // 避免 Long/Integer 在执行 Lua 前触发 ClassCastException，同时不改变脚本中的 tonumber 语义。
            Object[] serializedArguments = java.util.Arrays.stream(arguments)
                    .map(String::valueOf)
                    .toArray();
            return redis.execute(script, keys, serializedArguments);
        } catch (DataAccessException exception) {
            throw new BusinessException(ErrorCode.QUOTA_UNAVAILABLE);
        }
    }

    private BusinessException rejection(Long code) {
        if (code != null && code == TPM_REJECTED) {
            return new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "TPM 限额已用尽", 60);
        }
        if (code != null && code == CONCURRENCY_REJECTED) {
            return new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "并发请求数已达到上限", 1);
        }
        if (code != null && code == RPM_REJECTED) {
            return new BusinessException(ErrorCode.RATE_LIMIT_EXCEEDED, "RPM 限额已用尽", 60);
        }
        return new BusinessException(ErrorCode.QUOTA_UNAVAILABLE);
    }

    public record ApiKeyLease(
            String tpmKey,
            String concurrencyKey,
            String subscriptionConcurrencyKey,
            long reservedTokens,
            boolean tpmEnabled,
            boolean concurrencyEnabled,
            boolean subscriptionConcurrencyEnabled
    ) {
    }

    public record ChannelLease(String key, boolean enabled) {
    }
}
