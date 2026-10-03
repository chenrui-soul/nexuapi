package com.nexusapi.server.modules.auth.service;

import com.nexusapi.server.common.crypto.AesGcmFieldCipher;
import com.nexusapi.server.common.crypto.HmacLookupHasher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.auth.dto.LoginRequest;
import com.nexusapi.server.modules.auth.dto.ChangePasswordRequest;
import com.nexusapi.server.modules.auth.dto.RegisterRequest;
import com.nexusapi.server.modules.auth.entity.UserAuthRow;
import com.nexusapi.server.modules.auth.enums.CaptchaScene;
import com.nexusapi.server.modules.auth.mapper.AuthUserMapper;
import com.nexusapi.server.modules.auth.security.AuthSessionService;
import com.nexusapi.server.modules.auth.support.EmailNormalizer;
import com.nexusapi.server.modules.auth.vo.AccountSecurityResponse;
import com.nexusapi.server.modules.system.PlatformSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {
    private final AuthUserMapper userMapper;
    private final CaptchaService captchaService;
    private final AuthRateLimitService rateLimitService;
    private final AuthAuditService auditService;
    private final EmailNormalizer emailNormalizer;
    private final AesGcmFieldCipher fieldCipher;
    private final HmacLookupHasher lookupHasher;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessionService;
    private final String dummyPasswordHash;
    private final PlatformSettingsService platformSettings;

    public AuthService(
            AuthUserMapper userMapper,
            CaptchaService captchaService,
            AuthRateLimitService rateLimitService,
            AuthAuditService auditService,
            EmailNormalizer emailNormalizer,
            AesGcmFieldCipher fieldCipher,
            HmacLookupHasher lookupHasher,
            PasswordEncoder passwordEncoder,
            AuthSessionService sessionService,
            PlatformSettingsService platformSettings
    ) {
        this.userMapper = userMapper;
        this.captchaService = captchaService;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.emailNormalizer = emailNormalizer;
        this.fieldCipher = fieldCipher;
        this.lookupHasher = lookupHasher;
        this.passwordEncoder = passwordEncoder;
        this.sessionService = sessionService;
        this.platformSettings = platformSettings;
        this.dummyPasswordHash = passwordEncoder.encode("NexusDummyPassword2026");
    }

    @Transactional
    public AuthenticatedUser register(RegisterRequest request, ClientRequestMetadata metadata) {
        platformSettings.requireRegistrationEnabled();
        rateLimitService.assertRegistrationAllowed(metadata.ipAddress());
        captchaService.verify(CaptchaScene.REGISTER, request.challengeId(), request.captchaCode());

        String normalizedEmail = emailNormalizer.normalize(request.email());
        byte[] emailHash = lookupHasher.hash("user-email", normalizedEmail);
        if (userMapper.findByEmailHash(emailHash) != null) {
            throw new BusinessException(ErrorCode.USER_EMAIL_EXISTS);
        }

        String displayName = normalizeDisplayName(request.name());
        UUID userId = UUID.randomUUID();
        try {
            userMapper.insertUser(
                    userId,
                    displayName,
                    fieldCipher.encrypt(normalizedEmail),
                    emailHash,
                    passwordEncoder.encode(request.password()),
                    metadata.ipAddress(),
                    metadata.userAgentHash()
            );
            userMapper.insertDefaultRole(userId);
            userMapper.insertWallet(userId);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ErrorCode.USER_EMAIL_EXISTS);
        }

        auditService.record(userId, "auth.register.success", userId.toString(), metadata);
        return currentUser(userId);
    }

    public AuthenticatedUser login(LoginRequest request, ClientRequestMetadata metadata) {
        String normalizedEmail = emailNormalizer.normalize(request.email());
        byte[] emailHash = lookupHasher.hash("user-email", normalizedEmail);
        String emailHashHex = HexFormat.of().formatHex(emailHash);

        rateLimitService.assertLoginAllowed(metadata.ipAddress(), emailHashHex);
        captchaService.verify(CaptchaScene.LOGIN, request.challengeId(), request.captchaCode());

        UserAuthRow row = userMapper.findByEmailHash(emailHash);
        String storedHash = row == null ? dummyPasswordHash : row.getPasswordHash();
        boolean passwordMatches;
        try {
            passwordMatches = passwordEncoder.matches(request.password(), storedHash);
        } catch (RuntimeException exception) {
            passwordMatches = false;
        }

        if (row == null || !passwordMatches) {
            rateLimitService.recordLoginFailure(metadata.ipAddress(), emailHashHex);
            auditService.record(null, "auth.login.failure", emailHashHex, metadata);
            throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        if (!"active".equals(row.getStatus())) {
            auditService.record(row.getId(), "auth.login.blocked", row.getId().toString(), metadata);
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }

        // A successful account login must not reset the shared IP risk counter.
        rateLimitService.clearAccountLoginFailures(emailHashHex);
        userMapper.markLastLogin(row.getId());
        auditService.record(row.getId(), "auth.login.success", row.getId().toString(), metadata);
        return toAuthenticatedUser(row);
    }

    public AuthenticatedUser currentUser(UUID userId) {
        UserAuthRow row = userMapper.findById(userId);
        if (row == null) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        if (!"active".equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        return toAuthenticatedUser(row);
    }

    public SessionPrincipalData loadSessionPrincipal(UUID userId) {
        UserAuthRow row = userMapper.findById(userId);
        if (row == null) {
            return null;
        }
        return new SessionPrincipalData(row.getId(), row.getDisplayName(), row.getStatus(), roles(row.getId()));
    }

    public void recordLogout(UUID userId, ClientRequestMetadata metadata) {
        auditService.record(userId, "auth.logout", userId.toString(), metadata);
    }

    /** 返回用户本人可见的安全摘要，不暴露任何会话标识和认证密钥。 */
    public AccountSecurityResponse security(UUID userId) {
        UserAuthRow row = requiredActiveUser(userId);
        return new AccountSecurityResponse(
                fieldCipher.decrypt(row.getEmailCiphertext()),
                row.getEmailVerifiedAt() != null,
                row.getLastLoginAt(),
                row.getPasswordChangedAt(),
                sessionService.count(userId)
        );
    }

    /** 校验当前密码后更新摘要，并保留发起操作的当前会话。 */
    public int changePassword(UUID userId, ChangePasswordRequest request, HttpServletRequest servletRequest,
                              ClientRequestMetadata metadata) {
        UserAuthRow row = requiredActiveUser(userId);
        if (!safeMatches(request.currentPassword(), row.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_CURRENT_PASSWORD_INVALID);
        }
        if (safeMatches(request.newPassword(), row.getPasswordHash())) {
            throw new BusinessException(ErrorCode.AUTH_NEW_PASSWORD_SAME);
        }
        if (userMapper.updatePassword(userId, passwordEncoder.encode(request.newPassword())) != 1) {
            throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        }
        int revoked = sessionService.revokeOthers(userId, servletRequest);
        auditService.record(userId, "auth.password.changed", userId.toString(), metadata);
        return revoked;
    }

    private UserAuthRow requiredActiveUser(UUID userId) {
        UserAuthRow row = userMapper.findById(userId);
        if (row == null) throw new BusinessException(ErrorCode.AUTH_SESSION_EXPIRED);
        if (!"active".equals(row.getStatus())) throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        return row;
    }

    private boolean safeMatches(String rawPassword, String encodedPassword) {
        try {
            return passwordEncoder.matches(rawPassword, encodedPassword);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private AuthenticatedUser toAuthenticatedUser(UserAuthRow row) {
        return new AuthenticatedUser(
                row.getId(),
                row.getDisplayName(),
                fieldCipher.decrypt(row.getEmailCiphertext()),
                row.getStatus(),
                roles(row.getId()),
                row.getCreatedAt()
        );
    }

    private List<String> roles(UUID userId) {
        return List.copyOf(userMapper.findRoles(userId));
    }

    private String normalizeDisplayName(String value) {
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "账号名称包含无效字符", null);
        }
        return normalized;
    }
}
