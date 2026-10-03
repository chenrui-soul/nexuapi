package com.nexusapi.server.modules.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.entity.AdminAuditLogRow;
import com.nexusapi.server.modules.admin.mapper.AdminAuditMapper;
import com.nexusapi.server.modules.admin.vo.AdminAuditLogResponse;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 统一记录管理员配置变更。
 *
 * <p>审计与业务写入共用调用方事务；任何敏感字段命中都会拒绝写入，防止凭证进入长期日志。</p>
 */
@Service
public class AdminAuditService {
    private static final int MAX_JSON_LENGTH = 16_384;
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "credential", "encryptedcredential", "secret", "token",
            "accesstoken", "refreshtoken", "apikey", "password"
    );
    private static final Set<String> SAFE_DERIVED_CREDENTIAL_KEYS = Set.of(
            "credentialconfigured", "credentialactive", "credentialfingerprint",
            "credentialupdatedat", "credentialkeyversion"
    );
    private static final Set<String> ACTOR_TYPES = Set.of("user", "admin", "system");
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AdminAuditMapper mapper;
    private final ObjectMapper objectMapper;

    public AdminAuditService(AdminAuditMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(
            UUID actorUserId,
            String action,
            String resourceType,
            UUID resourceId,
            Object before,
            Object after,
            ClientRequestMetadata metadata
    ) {
        mapper.insert(
                actorUserId,
                action,
                resourceType,
                resourceId.toString(),
                serialize(before),
                serialize(after),
                metadata.ipAddress(),
                metadata.userAgentHash(),
                metadata.requestId()
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAuditLogResponse> list(
            int page, int pageSize, String query, String resourceType, String actorType
    ) {
        String normalizedQuery = normalizeOptional(query, 100, "搜索内容");
        String normalizedResourceType = normalizeOptional(resourceType, 80, "资源类型");
        String normalizedActorType = normalizeOptional(actorType, 24, "主体类型");
        if (normalizedActorType != null && !ACTOR_TYPES.contains(normalizedActorType)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "主体类型不支持该值", null);
        }
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findPage(normalizedQuery, normalizedResourceType, normalizedActorType, offset, pageSize)
                        .stream().map(this::toResponse).toList(),
                mapper.countLogs(normalizedQuery, normalizedResourceType, normalizedActorType),
                page,
                pageSize
        );
    }

    private String serialize(Object value) {
        try {
            // 先转换为 JSON 树再检查字段名，确保 Map、DTO、record 和嵌套对象都使用同一套敏感字段规则。
            JsonNode jsonNode = objectMapper.valueToTree(value == null ? Map.of() : value);
            assertSafe(jsonNode);
            String json = objectMapper.writeValueAsString(jsonNode);
            if (json.length() > MAX_JSON_LENGTH) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "审计数据过大", null);
            }
            return json;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Failed to serialize admin audit data", exception);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize admin audit data", exception);
        }
    }

    private void assertSafe(JsonNode node) {
        if (node.isObject()) {
            node.properties().forEach(entry -> {
                String key = normalizeKey(entry.getKey());
                if (isSensitiveKey(key)) {
                    throw new BusinessException(ErrorCode.VALIDATION_ERROR, "审计数据不能包含敏感凭证字段", null);
                }
                assertSafe(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::assertSafe);
        }
    }

    private String normalizeKey(String key) {
        return key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private boolean isSensitiveKey(String key) {
        // 渠道响应只暴露“是否已配置”、指纹和更新时间，这些衍生字段不含凭证原文。
        if (SAFE_DERIVED_CREDENTIAL_KEYS.contains(key)) {
            return false;
        }
        return SENSITIVE_KEYS.stream()
                .anyMatch(marker -> key.equals(marker) || key.startsWith(marker) || key.endsWith(marker));
    }

    private AdminAuditLogResponse toResponse(AdminAuditLogRow row) {
        return new AdminAuditLogResponse(
                row.getId(), row.getActorUserId(), row.getActorDisplayName(), row.getActorType(),
                row.getAction(), row.getResourceType(), row.getResourceId(),
                parseObject(row.getBeforeJson()), parseObject(row.getAfterJson()), row.getIpAddress(),
                row.getUserAgentHash(), row.getRequestId(), row.getCreatedAt()
        );
    }

    private Map<String, Object> parseObject(String value) {
        try {
            return value == null || value.isBlank() ? Map.of() : objectMapper.readValue(value, OBJECT_MAP);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException("Failed to parse admin audit data", exception);
        }
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > maxLength || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "格式无效", null);
        }
        return normalized;
    }
}
