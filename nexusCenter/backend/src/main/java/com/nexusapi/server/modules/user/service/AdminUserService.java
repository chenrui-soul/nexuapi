package com.nexusapi.server.modules.user.service;

import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.crypto.AesGcmFieldCipher;
import com.nexusapi.server.common.crypto.HmacLookupHasher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.auth.support.EmailNormalizer;
import com.nexusapi.server.modules.user.dto.AdminUserRequest;
import com.nexusapi.server.modules.user.entity.AdminUserRow;
import com.nexusapi.server.modules.user.mapper.AdminUserMapper;
import com.nexusapi.server.modules.user.vo.AdminUserResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

@Service
public class AdminUserService {
    private static final Set<String> STATUSES = Set.of("active", "pending", "suspended", "locked");
    private static final Set<String> ROLES = Set.of("user", "operator", "admin");

    private final AdminUserMapper mapper;
    private final AdminAuditService auditService;
    private final EmailNormalizer emailNormalizer;
    private final HmacLookupHasher lookupHasher;
    private final AesGcmFieldCipher fieldCipher;

    public AdminUserService(
            AdminUserMapper mapper,
            AdminAuditService auditService,
            EmailNormalizer emailNormalizer,
            HmacLookupHasher lookupHasher,
            AesGcmFieldCipher fieldCipher
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.emailNormalizer = emailNormalizer;
        this.lookupHasher = lookupHasher;
        this.fieldCipher = fieldCipher;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> listUsers(
            int page, int pageSize, String query, String status, String role
    ) {
        String normalizedQuery = normalizeOptional(query, 100, "搜索内容");
        String normalizedStatus = enumOptional(status, STATUSES, "用户状态");
        String normalizedRole = enumOptional(role, ROLES, "用户角色");
        byte[] emailHash = null;
        String textQuery = normalizedQuery;
        if (normalizedQuery != null && normalizedQuery.contains("@")) {
            String email = emailNormalizer.normalize(normalizedQuery);
            emailHash = lookupHasher.hash("user-email", email);
            textQuery = null;
        }
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findPage(textQuery, emailHash, normalizedStatus, normalizedRole, offset, pageSize)
                        .stream().map(this::toResponse).toList(),
                mapper.countUsers(textQuery, emailHash, normalizedStatus, normalizedRole),
                page,
                pageSize
        );
    }

    @Transactional
    public AdminUserResponse updateUser(
            UUID actorUserId, UUID userId, AdminUserRequest request, ClientRequestMetadata metadata
    ) {
        AdminUserRow existing = requireUser(userId);
        if (request.version() == null) {
            throw validation("更新用户必须提供 version");
        }
        String displayName = normalizeRequired(request.displayName(), 80, "用户名称");
        String status = enumValue(request.status(), STATUSES, "用户状态");
        TreeSet<String> roles = new TreeSet<>();
        request.roles().forEach(role -> roles.add(enumValue(role, ROLES, "用户角色")));
        roles.add("user");

        // 当前管理员不能撤销自己的管理权限或停用自己的账号，防止唯一管理员把控制台锁死。
        if (actorUserId.equals(userId) && (!"active".equals(status) || !roles.contains("admin"))) {
            throw new BusinessException(ErrorCode.USER_SELF_PROTECTION);
        }
        if (mapper.updateUser(userId, displayName, status, request.version()) != 1) {
            throw new BusinessException(ErrorCode.USER_VERSION_CONFLICT);
        }
        mapper.deleteRoles(userId);
        mapper.insertRoles(userId, roles, actorUserId);
        AdminUserResponse updated = toResponse(requireUser(userId));
        auditService.record(actorUserId, "admin.user.update", "user", userId,
                toResponse(existing), updated, metadata);
        return updated;
    }

    private AdminUserRow requireUser(UUID id) {
        AdminUserRow row = mapper.findById(id);
        if (row == null) throw new BusinessException(ErrorCode.USER_NOT_FOUND);
        return row;
    }

    private AdminUserResponse toResponse(AdminUserRow row) {
        return new AdminUserResponse(
                row.getId(), row.getDisplayName(), maskEmail(fieldCipher.decrypt(row.getEmailCiphertext())),
                row.getStatus(), parseRoles(row.getRolesCsv()), row.getEmailVerifiedAt() != null,
                row.getLastLoginAt(), row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private List<String> parseRoles(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.copyOf(new ArrayList<>(List.of(value.split(","))));
    }

    private String maskEmail(String email) {
        int separator = email.indexOf('@');
        if (separator <= 0) return "***";
        String local = email.substring(0, separator);
        String domain = email.substring(separator + 1);
        String maskedLocal = local.length() <= 2
                ? local.charAt(0) + "***"
                : local.substring(0, Math.min(2, local.length())) + "***" + local.substring(Math.max(2, local.length() - 2));
        int dot = domain.lastIndexOf('.');
        String domainName = dot > 0 ? domain.substring(0, dot) : domain;
        String suffix = dot > 0 ? domain.substring(dot) : "";
        String maskedDomain = domainName.isEmpty() ? "***" : domainName.charAt(0) + "***";
        return maskedLocal + "@" + maskedDomain + suffix;
    }

    private String enumOptional(String value, Set<String> allowed, String field) {
        return value == null || value.isBlank() ? null : enumValue(value, allowed, field);
    }

    private String enumValue(String value, Set<String> allowed, String field) {
        String normalized = normalizeRequired(value, 32, field).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw validation(field + "不支持该值");
        return normalized;
    }

    private String normalizeRequired(String value, int maxLength, String field) {
        String normalized = normalizeOptional(value, maxLength, field);
        if (normalized == null) throw validation(field + "不能为空");
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw validation(field + "格式无效");
        }
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
