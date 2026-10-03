package com.nexusapi.server.modules.auth.service;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.crypto.HmacLookupHasher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Service
public class AuthRateLimitService {
    private static final int REGISTRATION_LIMIT_PER_HOUR = 10;

    private final StringRedisTemplate redis;
    private final HmacLookupHasher hasher;
    private final NexusProperties.Auth properties;

    public AuthRateLimitService(StringRedisTemplate redis, HmacLookupHasher hasher, NexusProperties nexusProperties) {
        this.redis = redis;
        this.hasher = hasher;
        this.properties = nexusProperties.auth();
    }

    public void assertLoginAllowed(String ipAddress, String emailHashHex) {
        if (count(loginIpKey(ipAddress)) >= properties.loginMaxFailures()
                || count(loginAccountKey(emailHashHex)) >= properties.loginMaxFailures()) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
        }
    }

    public void recordLoginFailure(String ipAddress, String emailHashHex) {
        incrementWithTtl(loginIpKey(ipAddress), properties.loginFailureWindow());
        incrementWithTtl(loginAccountKey(emailHashHex), properties.loginFailureWindow());
    }

    public void clearAccountLoginFailures(String emailHashHex) {
        redis.delete(loginAccountKey(emailHashHex));
    }

    public void assertRegistrationAllowed(String ipAddress) {
        long hour = System.currentTimeMillis() / 3_600_000L;
        String ipHash = hasher.hashHex("register-ip", ipAddress);
        String key = "nexus:auth:register:" + hour + ':' + ipHash;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, 2, TimeUnit.HOURS);
        }
        if (count != null && count > REGISTRATION_LIMIT_PER_HOUR) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
        }
    }

    /** 找回密码同时限制来源 IP 与邮箱摘要，避免邮件轰炸和账号探测。 */
    public void assertPasswordResetAllowed(String ipAddress, String emailHashHex) {
        long hour = System.currentTimeMillis() / 3_600_000L;
        assertHourlyLimit("reset:ip:" + hasher.hashHex("reset-ip", ipAddress), hour);
        assertHourlyLimit("reset:account:" + emailHashHex, hour);
    }

    private void assertHourlyLimit(String dimension, long hour) {
        String key = "nexus:auth:" + dimension + ':' + hour;
        Long current = redis.opsForValue().increment(key);
        if (current != null && current == 1L) {
            redis.expire(key, 2, TimeUnit.HOURS);
        }
        if (current != null && current > properties.passwordResetRequestsPerHour()) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
        }
    }

    private long count(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return 0;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            redis.delete(key);
            return 0;
        }
    }

    private void incrementWithTtl(String key, Duration ttl) {
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, ttl);
        }
    }

    private String loginIpKey(String ipAddress) {
        return "nexus:auth:login:ip:" + hasher.hashHex("login-ip", ipAddress);
    }

    private String loginAccountKey(String emailHashHex) {
        return "nexus:auth:login:account:" + emailHashHex;
    }
}
