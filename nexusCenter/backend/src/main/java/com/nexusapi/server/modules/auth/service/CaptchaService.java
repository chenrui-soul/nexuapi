package com.nexusapi.server.modules.auth.service;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.crypto.HmacLookupHasher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.enums.CaptchaScene;
import com.nexusapi.server.modules.auth.support.CaptchaSvgRenderer;
import com.nexusapi.server.modules.auth.vo.CaptchaChallenge;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CaptchaService {
    private static final DefaultRedisScript<Long> VERIFY_SCRIPT = new DefaultRedisScript<>("""
            local expected = redis.call('HGET', KEYS[1], 'digest')
            if not expected then return 0 end
            if expected == ARGV[1] then
              redis.call('DEL', KEYS[1])
              return 1
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[2]) then redis.call('DEL', KEYS[1]) end
            return -1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final CaptchaCodeGenerator codeGenerator;
    private final CaptchaSvgRenderer renderer;
    private final HmacLookupHasher hasher;
    private final NexusProperties.Auth properties;

    public CaptchaService(
            StringRedisTemplate redis,
            CaptchaCodeGenerator codeGenerator,
            CaptchaSvgRenderer renderer,
            HmacLookupHasher hasher,
            NexusProperties nexusProperties
    ) {
        this.redis = redis;
        this.codeGenerator = codeGenerator;
        this.renderer = renderer;
        this.hasher = hasher;
        this.properties = nexusProperties.auth();
    }

    public CaptchaChallenge issue(CaptchaScene scene, String clientAddress) {
        enforceIssueRate(clientAddress);
        String challengeId = UUID.randomUUID().toString();
        String code = codeGenerator.generate(4);
        String digest = digest(scene, challengeId, code);
        String key = challengeKey(scene, challengeId);
        redis.opsForHash().put(key, "digest", digest);
        redis.opsForHash().put(key, "attempts", "0");
        redis.expire(key, properties.captchaTtl());
        return new CaptchaChallenge(challengeId, renderer.renderDataUri(code), properties.captchaTtl().toSeconds());
    }

    public void verify(CaptchaScene scene, String challengeId, String suppliedCode) {
        String normalizedId = normalizeChallengeId(challengeId);
        String normalizedCode = suppliedCode == null ? "" : suppliedCode.trim().toUpperCase(Locale.ROOT);
        String providedDigest = digest(scene, normalizedId, normalizedCode);
        Long result = redis.execute(
                VERIFY_SCRIPT,
                List.of(challengeKey(scene, normalizedId)),
                providedDigest,
                Integer.toString(properties.captchaMaxAttempts())
        );
        if (result == null || result != 1L) {
            throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
        }
    }

    private void enforceIssueRate(String clientAddress) {
        long minute = System.currentTimeMillis() / 60_000L;
        String clientHash = hasher.hashHex("captcha-client", clientAddress == null ? "unknown" : clientAddress);
        String key = "nexus:auth:captcha:issue:" + minute + ':' + clientHash;
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1L) {
            redis.expire(key, 2, TimeUnit.MINUTES);
        }
        if (count != null && count > properties.captchaIssuesPerMinute()) {
            throw new BusinessException(ErrorCode.AUTH_TOO_MANY_ATTEMPTS);
        }
    }

    private String digest(CaptchaScene scene, String challengeId, String code) {
        return hasher.hashHex("captcha", scene.value() + ':' + challengeId + ':' + code);
    }

    private String challengeKey(CaptchaScene scene, String challengeId) {
        return "nexus:auth:captcha:" + scene.value() + ':' + challengeId;
    }

    private String normalizeChallengeId(String challengeId) {
        try {
            return UUID.fromString(challengeId).toString();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUTH_CAPTCHA_INVALID);
        }
    }
}
