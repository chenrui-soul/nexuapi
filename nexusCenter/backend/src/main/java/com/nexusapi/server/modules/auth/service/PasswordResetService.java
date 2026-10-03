package com.nexusapi.server.modules.auth.service;

import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.crypto.HmacLookupHasher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.dto.PasswordForgotRequest;
import com.nexusapi.server.modules.auth.dto.PasswordResetRequest;
import com.nexusapi.server.modules.auth.entity.UserAuthRow;
import com.nexusapi.server.modules.auth.enums.CaptchaScene;
import com.nexusapi.server.modules.auth.mapper.AuthUserMapper;
import com.nexusapi.server.modules.auth.security.AuthSessionService;
import com.nexusapi.server.modules.auth.support.EmailNormalizer;
import com.nexusapi.server.modules.auth.vo.PasswordResetChallenge;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/** 完成“图形验证码 → 邮件验证码 → 一次性重置 → 撤销旧会话”的安全闭环。 */
@Service
public class PasswordResetService {
    private static final String DECOY_USER = "decoy";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DefaultRedisScript<String> CONSUME_SCRIPT = new DefaultRedisScript<>("""
            local expected = redis.call('HGET', KEYS[1], 'digest')
            if not expected then return 'missing' end
            if expected == ARGV[1] then
              local userId = redis.call('HGET', KEYS[1], 'user_id')
              redis.call('DEL', KEYS[1])
              return userId or 'decoy'
            end
            local attempts = redis.call('HINCRBY', KEYS[1], 'attempts', 1)
            if attempts >= tonumber(ARGV[2]) then redis.call('DEL', KEYS[1]) end
            return 'invalid'
            """, String.class);

    private final StringRedisTemplate redis;
    private final AuthUserMapper userMapper;
    private final CaptchaService captchaService;
    private final AuthRateLimitService rateLimitService;
    private final PasswordResetDelivery delivery;
    private final EmailNormalizer emailNormalizer;
    private final HmacLookupHasher hasher;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessionService;
    private final AuthAuditService auditService;
    private final NexusProperties.Auth properties;

    public PasswordResetService(
            StringRedisTemplate redis,
            AuthUserMapper userMapper,
            CaptchaService captchaService,
            AuthRateLimitService rateLimitService,
            PasswordResetDelivery delivery,
            EmailNormalizer emailNormalizer,
            HmacLookupHasher hasher,
            PasswordEncoder passwordEncoder,
            AuthSessionService sessionService,
            AuthAuditService auditService,
            NexusProperties nexusProperties
    ) {
        this.redis = redis;
        this.userMapper = userMapper;
        this.captchaService = captchaService;
        this.rateLimitService = rateLimitService;
        this.delivery = delivery;
        this.emailNormalizer = emailNormalizer;
        this.hasher = hasher;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.auditService = auditService;
        this.properties = nexusProperties.auth();
    }

    public PasswordResetChallenge issue(PasswordForgotRequest request, ClientRequestMetadata metadata) {
        // 必须先检查投递通道，再读取账户；无 SMTP 配置时所有邮箱得到相同响应。
        delivery.assertAvailable();
        String normalizedEmail = emailNormalizer.normalize(request.email());
        String emailHashHex = hasher.hashHex("user-email", normalizedEmail);
        rateLimitService.assertPasswordResetAllowed(metadata.ipAddress(), emailHashHex);
        captchaService.verify(CaptchaScene.PASSWORD_RESET, request.challengeId(), request.captchaCode());

        UserAuthRow user = userMapper.findByEmailHash(hasher.hash("user-email", normalizedEmail));
        String resetId = UUID.randomUUID().toString();
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String key = resetKey(resetId);
        redis.opsForHash().put(key, "digest", resetDigest(resetId, emailHashHex, code));
        redis.opsForHash().put(key, "user_id", user != null && "active".equals(user.getStatus())
                ? user.getId().toString() : DECOY_USER);
        redis.opsForHash().put(key, "attempts", "0");
        redis.expire(key, properties.passwordResetTtl());

        if (user != null && "active".equals(user.getStatus())) {
            try {
                delivery.sendCode(normalizedEmail, code, properties.passwordResetTtl());
            } catch (RuntimeException exception) {
                redis.delete(key);
                throw exception;
            }
            auditService.record(user.getId(), "auth.password_reset.requested", user.getId().toString(), metadata);
        }
        return new PasswordResetChallenge(resetId, properties.passwordResetTtl().toSeconds());
    }

    @Transactional
    public void reset(PasswordResetRequest request, ClientRequestMetadata metadata) {
        String resetId = normalizeResetId(request.resetId());
        String normalizedEmail = emailNormalizer.normalize(request.email());
        String emailHashHex = hasher.hashHex("user-email", normalizedEmail);
        String result = redis.execute(
                CONSUME_SCRIPT,
                List.of(resetKey(resetId)),
                resetDigest(resetId, emailHashHex, request.verificationCode()),
                Integer.toString(properties.passwordResetMaxAttempts())
        );
        if (result == null || "missing".equals(result) || "invalid".equals(result) || DECOY_USER.equals(result)) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_INVALID);
        }

        UUID userId;
        try {
            userId = UUID.fromString(result);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_INVALID);
        }
        if (userMapper.resetPassword(userId, passwordEncoder.encode(request.newPassword())) != 1) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_INVALID);
        }
        sessionService.revokeAll(userId);
        auditService.record(userId, "auth.password_reset.completed", userId.toString(), metadata);
    }

    private String resetDigest(String resetId, String emailHashHex, String code) {
        return hasher.hashHex("password-reset", resetId + ':' + emailHashHex + ':' + code);
    }

    private String resetKey(String resetId) {
        return "nexus:auth:password-reset:" + resetId;
    }

    private String normalizeResetId(String resetId) {
        try {
            return UUID.fromString(resetId).toString();
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AUTH_PASSWORD_RESET_INVALID);
        }
    }
}
