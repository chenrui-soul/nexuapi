package com.nexusapi.server.modules.routing.service;

import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.crypto.AesGcmFieldCipher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.channel.security.ChannelCredentialCipher;
import com.nexusapi.server.modules.routing.dto.AdminGroupConfigurationRequest;
import com.nexusapi.server.modules.routing.dto.AdminGroupSupplierCredentialRequest;
import com.nexusapi.server.modules.routing.dto.AdminGroupUserGrantRequest;
import com.nexusapi.server.modules.routing.dto.AdminRoutingGroupRequest;
import com.nexusapi.server.modules.routing.entity.AdminGroupSupplierCredentialRow;
import com.nexusapi.server.modules.routing.entity.AdminGroupUserGrantRow;
import com.nexusapi.server.modules.routing.entity.AdminRoutingGroupRow;
import com.nexusapi.server.modules.routing.mapper.AdminRoutingMapper;
import com.nexusapi.server.modules.routing.vo.AdminGroupConfigurationResponse;
import com.nexusapi.server.modules.routing.vo.AdminGroupSupplierCredentialResponse;
import com.nexusapi.server.modules.routing.vo.AdminGroupUserGrantResponse;
import com.nexusapi.server.modules.routing.vo.AdminRoutingGroupResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 计费分组、倍率和分组到上游映射的业务层。 */
@Service
public class AdminRoutingService {
    private static final Pattern GROUP_CODE = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,63}$");
    private static final Set<String> AUDIENCES = Set.of("all", "assigned", "internal");
    private static final Set<String> MANUAL_GROUP_STATUSES = Set.of("active", "disabled");
    private static final Set<String> ALL_GROUP_STATUSES = Set.of("active", "disabled", "degraded");

    private final AdminRoutingMapper mapper;
    private final ChannelCredentialCipher credentialCipher;
    private final AdminAuditService auditService;
    private final AesGcmFieldCipher fieldCipher;

    public AdminRoutingService(
            AdminRoutingMapper mapper,
            ChannelCredentialCipher credentialCipher,
            AdminAuditService auditService,
            AesGcmFieldCipher fieldCipher
    ) {
        this.mapper = mapper;
        this.credentialCipher = credentialCipher;
        this.auditService = auditService;
        this.fieldCipher = fieldCipher;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRoutingGroupResponse> listGroups(int page, int pageSize, String query, String status) {
        String normalizedQuery = normalizeOptionalText(query, 100, "搜索内容");
        String normalizedStatus = status == null || status.isBlank() ? null : enumValue(status, ALL_GROUP_STATUSES, "分组状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findGroupPage(normalizedQuery, normalizedStatus, offset, pageSize).stream().map(this::toGroupResponse).toList(),
                mapper.countGroups(normalizedQuery, normalizedStatus), page, pageSize
        );
    }

    @Transactional
    public AdminRoutingGroupResponse createGroup(
            UUID actorUserId,
            AdminRoutingGroupRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminRoutingGroupRow row = normalizeGroup(request, false);
        row.setId(UUID.randomUUID());
        assertGroupCodeUnique(row.getCode(), null);
        mapper.insertGroup(row);
        AdminRoutingGroupResponse created = toGroupResponse(requireGroup(row.getId()));
        auditService.record(actorUserId, "admin.routing_group.create", "routing_group", row.getId(), null, created, metadata);
        return created;
    }

    @Transactional
    public AdminRoutingGroupResponse updateGroup(
            UUID actorUserId,
            UUID id,
            AdminRoutingGroupRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminRoutingGroupRow existing = requireGroup(id);
        if (request.version() == null) {
            throw validation("更新分组必须提供 version");
        }
        AdminRoutingGroupRow row = normalizeGroup(request, true);
        row.setId(id);
        assertGroupCodeUnique(row.getCode(), id);
        if (mapper.updateGroup(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        if ("assigned".equals(existing.getAudience()) && !"assigned".equals(row.getAudience())) {
            mapper.revokeAllGroupUserGrants(id);
        }
        AdminRoutingGroupResponse updated = toGroupResponse(requireGroup(id));
        auditService.record(actorUserId, "admin.routing_group.update", "routing_group", id,
                toGroupResponse(existing), updated, metadata);
        return updated;
    }

    /** 读取特殊分组当前有效用户；邮箱只在业务层解密并立即转换为脱敏文本。 */
    @Transactional(readOnly = true)
    public List<AdminGroupUserGrantResponse> listGroupUserGrants(UUID groupId) {
        assertAssignedGroup(requireGroup(groupId));
        return mapper.findActiveGroupUserGrants(groupId).stream().map(this::toUserGrantResponse).toList();
    }

    /** 原子替换特殊分组授权名单，撤销关系保留历史但下一次 API 令牌鉴权会立即失效。 */
    @Transactional
    public List<AdminGroupUserGrantResponse> updateGroupUserGrants(
            UUID actorUserId,
            UUID groupId,
            AdminGroupUserGrantRequest request,
            ClientRequestMetadata metadata
    ) {
        assertAssignedGroup(requireGroup(groupId));
        List<UUID> userIds = List.copyOf(new LinkedHashSet<>(request.userIds()));
        if (!userIds.isEmpty() && mapper.countActiveUsers(userIds) != userIds.size()) {
            throw validation("只能授权当前有效且未删除的用户");
        }
        if (userIds.isEmpty()) {
            mapper.revokeAllGroupUserGrants(groupId);
        } else {
            mapper.revokeMissingGroupUserGrants(groupId, userIds);
            mapper.upsertGroupUserGrants(groupId, userIds, actorUserId);
        }
        List<AdminGroupUserGrantResponse> updated = mapper.findActiveGroupUserGrants(groupId)
                .stream().map(this::toUserGrantResponse).toList();
        Map<String, Object> auditSummary = new java.util.LinkedHashMap<>();
        auditSummary.put("authorized_user_count", updated.size());
        auditSummary.put("user_ids", updated.stream().map(AdminGroupUserGrantResponse::userId).limit(500).toList());
        auditService.record(actorUserId, "admin.routing_group.user_grants.update", "routing_group",
                groupId, null, auditSummary, metadata);
        return updated;
    }

    /** 一次读取分组、全部模型路由和凭证安全状态，管理端不需要接触任何密文。 */
    @Transactional(readOnly = true)
    public AdminGroupConfigurationResponse getConfiguration(UUID groupId) {
        AdminRoutingGroupRow group = requireGroup(groupId);
        return configurationResponse(group);
    }

    /**
     * 原子替换分组的供应商资源、上游凭证和开放模型。
     *
     * <p>这里只保存分组资源；模型与供应商接口是否可调用由 Gateway 在请求时动态判断。</p>
     */
    @Transactional
    public AdminGroupConfigurationResponse updateConfiguration(
            UUID actorUserId,
            UUID groupId,
            AdminGroupConfigurationRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminRoutingGroupRow existingGroup = requireGroup(groupId);
        AdminGroupConfigurationResponse before = configurationResponse(existingGroup);
        if (mapper.touchGroupConfiguration(groupId, request.groupVersion()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }

        if (request.modelIds() == null) {
            throw validation("服务分组配置必须提交 model_ids");
        }
        List<UUID> selectedModelIds = normalizeSelectedModelIds(request.modelIds());
        Map<UUID, AdminGroupSupplierCredentialRequest> requestedSuppliers =
                normalizeSupplierRequests(request.supplierCredentials(), true);
        // 同步分组的上游目录是默认模型来源，但管理员仍可以追加人工模型。
        // 只标记 source_type=manual 的关系为 stale，因此不会覆盖或删除同步模型。
        // 人工追加的模型也不会被下一次上游目录同步清理。
        syncManualGroupModels(groupId, selectedModelIds);
        syncSuppliers(groupId, requestedSuppliers);
        AdminGroupConfigurationResponse updated = configurationResponse(requireGroup(groupId));
        // 审计只记录脱敏响应；原始请求中的上游 APIKey 绝不能进入审计表。
        auditService.record(actorUserId, "admin.routing_group.configuration.update", "routing_group",
                groupId, before, updated, metadata);
        return updated;
    }

    /** 对供应商关系先去重并校验状态，Gateway 动态路由只允许使用这份可信集合。 */
    private Map<UUID, AdminGroupSupplierCredentialRequest> normalizeSupplierRequests(
            List<AdminGroupSupplierCredentialRequest> requests,
            boolean requireSupplier
    ) {
        Map<UUID, AdminGroupSupplierCredentialRequest> requestedBySupplier = new HashMap<>();
        for (AdminGroupSupplierCredentialRequest request : requests) {
            if (requestedBySupplier.put(request.supplierId(), request) != null) {
                throw validation("同一供应商的分组资源不能重复配置");
            }
            if (mapper.countActiveSupplier(request.supplierId()) != 1) {
                throw validation("服务分组只能关联启用状态的供应商");
            }
        }
        if (requireSupplier && requestedBySupplier.isEmpty()) {
            throw validation("服务分组必须至少关联一个启用状态的供应商");
        }
        return requestedBySupplier;
    }

    /** 保存人工服务分组的模型目录快照，取消选择只标记 stale，保留历史关系。 */
    private void syncManualGroupModels(UUID groupId, List<UUID> selectedModelIds) {
        mapper.markMissingManualGroupModelsStale(groupId, selectedModelIds);
        if (!selectedModelIds.isEmpty()) {
            mapper.upsertManualGroupModels(groupId, selectedModelIds);
        }
    }

    private List<UUID> normalizeSelectedModelIds(List<UUID> modelIds) {
        List<UUID> normalized = List.copyOf(new LinkedHashSet<>(modelIds));
        if (!normalized.isEmpty() && mapper.countModels(normalized) != normalized.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "所选模型不存在", null);
        }
        return normalized;
    }

    /** 服务分组直接维护供应商关系；模型路由只能使用已显式选择的供应商。 */
    private void syncSuppliers(
            UUID groupId,
            Map<UUID, AdminGroupSupplierCredentialRequest> requestedBySupplier
    ) {
        Map<UUID, AdminGroupSupplierCredentialRow> existingBySupplier = new HashMap<>();
        for (AdminGroupSupplierCredentialRow row : mapper.findGroupSupplierCredentials(groupId)) {
            existingBySupplier.put(row.getSupplierId(), row);
            if (row.getRelationId() == null) {
                continue;
            }
            AdminGroupSupplierCredentialRequest requested = requestedBySupplier.get(row.getSupplierId());
            row.setRelationStatus(requested == null ? "disabled" : "active");
            if (requested != null) {
                row.setPriority(requested.priority());
                row.setWeight(requested.weight());
            }
            if (mapper.updateGroupSupplier(row) != 1) {
                throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
            }
            if (requested == null && row.getCredentialId() != null
                    && !"disabled".equals(row.getCredentialStatus())) {
                row.setCredentialStatus("disabled");
                updateSupplierCredential(row);
            }
        }

        for (AdminGroupSupplierCredentialRequest requested : requestedBySupplier.values()) {
            UUID supplierId = requested.supplierId();
            AdminGroupSupplierCredentialRow row = existingBySupplier.get(supplierId);
            if (row == null || row.getRelationId() == null) {
                AdminGroupSupplierCredentialRow created = row == null ? new AdminGroupSupplierCredentialRow() : row;
                created.setRelationId(UUID.randomUUID());
                created.setGroupId(groupId);
                created.setSupplierId(supplierId);
                created.setPriority(requested.priority());
                created.setWeight(requested.weight());
                created.setRelationStatus("active");
                created.setRelationVersion(0L);
                mapper.insertGroupSupplier(created);
                existingBySupplier.put(supplierId, created);
            }
            syncSupplierCredential(groupId, requested, existingBySupplier.get(supplierId));
        }
    }

    private void syncSupplierCredential(
            UUID groupId,
            AdminGroupSupplierCredentialRequest request,
            AdminGroupSupplierCredentialRow relation
    ) {
        AdminGroupSupplierCredentialRow existing = mapper.findGroupSupplierCredential(groupId, request.supplierId());
        AdminGroupSupplierCredentialRow row = existing == null ? relation : existing;
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "分组供应商关系不存在", null);
        }

        String plaintext = normalizeCredential(request.credential());
        if (row.getCredentialId() == null && plaintext == null) {
            throw validation("首次关联供应商时必须填写该服务分组的上游 APIKey");
        }
        // 先保存新增判断；row 与 existing 可能是同一对象，设置 ID 后再判断会误走 UPDATE。
        boolean createCredential = row.getCredentialId() == null;
        if (createCredential) {
            row.setCredentialId(UUID.randomUUID());
            row.setGroupId(groupId);
            row.setSupplierId(request.supplierId());
            row.setCredentialVersion(0L);
        }
        row.setCredentialStatus("active");
        if (plaintext != null) {
            applySupplierCredential(row, plaintext);
        }
        if (createCredential) {
            mapper.insertGroupSupplierCredential(row);
        } else {
            updateSupplierCredential(row);
        }
    }

    private void applySupplierCredential(AdminGroupSupplierCredentialRow row, String plaintext) {
        try {
            ChannelCredentialCipher.EncryptedCredential encrypted = credentialCipher.encrypt(plaintext);
            row.setEncryptedCredential(encrypted.ciphertext());
            row.setCredentialKeyVersion(encrypted.keyVersion());
            row.setCredentialFingerprint(encrypted.fingerprint());
        } catch (IllegalArgumentException exception) {
            throw validation("分组供应商上游 APIKey 格式无效");
        }
    }

    private void updateSupplierCredential(AdminGroupSupplierCredentialRow row) {
        if (mapper.updateGroupSupplierCredential(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
    }

    private String normalizeCredential(String credential) {
        if (credential == null || credential.isBlank()) {
            return null;
        }
        String normalized = credential.strip();
        if (normalized.length() > 4_096 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw validation("分组上游 APIKey 格式无效");
        }
        return normalized;
    }

    private AdminGroupConfigurationResponse configurationResponse(AdminRoutingGroupRow group) {
        List<UUID> defaultModelIds = mapper.findActiveGroupModelIds(group.getId());
        List<AdminGroupSupplierCredentialResponse> supplierCredentials =
                mapper.findGroupSupplierCredentials(group.getId()).stream()
                        .filter(row -> row.getRelationId() != null && "active".equals(row.getRelationStatus()))
                        .map(this::toSupplierCredentialResponse)
                        .toList();
        return new AdminGroupConfigurationResponse(
                toGroupResponse(group), defaultModelIds, supplierCredentials
        );
    }

    private AdminGroupSupplierCredentialResponse toSupplierCredentialResponse(AdminGroupSupplierCredentialRow row) {
        boolean configured = row.getCredentialId() != null && row.getCredentialFingerprint() != null;
        return new AdminGroupSupplierCredentialResponse(
                row.getSupplierId(), row.getSupplierName(), row.getPriority(), row.getWeight(),
                configured, configured && "active".equals(row.getCredentialStatus()),
                row.getCredentialFingerprint(), row.getCredentialUpdatedAt()
        );
    }

    private AdminRoutingGroupRow normalizeGroup(AdminRoutingGroupRequest request, boolean requireVersion) {
        String code = normalizeText(request.code(), 64, "分组编码").toLowerCase(Locale.ROOT);
        if (!GROUP_CODE.matcher(code).matches()) {
            throw validation("分组编码只能包含小写字母、数字、下划线和连字符");
        }
        AdminRoutingGroupRow row = new AdminRoutingGroupRow();
        row.setCode(code);
        row.setName(normalizeText(request.name(), 120, "分组名称"));
        row.setDescription(normalizeOptionalText(request.description(), 500, "分组描述"));
        row.setPriceMultiplier(multiplier(request.priceMultiplier()));
        row.setAudience(enumValue(request.audience(), AUDIENCES, "分组受众"));
        row.setStatus(enumValue(request.status(), MANUAL_GROUP_STATUSES, "分组状态"));
        row.setVersion(requireVersion ? request.version() : 0L);
        return row;
    }

    private BigDecimal multiplier(BigDecimal value) {
        try {
            return value.setScale(6, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw validation("分组倍率最多保留 6 位小数");
        }
    }

    private void assertGroupCodeUnique(String code, UUID excludedId) {
        if (mapper.countGroupCode(code, excludedId) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "分组编码已存在", null);
        }
    }

    private AdminRoutingGroupRow requireGroup(UUID id) {
        AdminRoutingGroupRow row = mapper.findGroupById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "分组不存在", null);
        }
        return row;
    }

    private AdminRoutingGroupResponse toGroupResponse(AdminRoutingGroupRow row) {
        return new AdminRoutingGroupResponse(
                row.getId(), row.getCode(), row.getName(), row.getDescription(), row.getPriceMultiplier(),
                row.getAudience(), row.getStatus(), row.getSourceSupplierId(), row.getSourceSupplierName(),
                row.getSourceGroupId(), row.getSourceGroupName(), row.getSyncSource(), row.getSourceStatus(),
                row.getSourceRate(), row.getSourceBillingType(), row.isSourceManaged(), row.getSourceLastSeenAt(),
                row.getSourceSyncedAt(), row.getSourceModelCount(), row.getHealthyModelCount(),
                row.getStaleModelCount(), row.getAuthorizedUserCount(), row.isCredentialConfigured(),
                row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private void assertAssignedGroup(AdminRoutingGroupRow group) {
        if (!"assigned".equals(group.getAudience())) {
            throw validation("只有“需授权使用”的服务分组可以维护用户授权名单");
        }
    }

    private AdminGroupUserGrantResponse toUserGrantResponse(AdminGroupUserGrantRow row) {
        return new AdminGroupUserGrantResponse(
                row.getUserId(), row.getDisplayName(), maskEmail(fieldCipher.decrypt(row.getEmailCiphertext())),
                row.getUserStatus(), row.getExpiresAt(), row.getCreatedAt(), row.getUpdatedAt()
        );
    }

    private String maskEmail(String email) {
        int separator = email.indexOf('@');
        if (separator <= 0) return "***";
        String local = email.substring(0, separator);
        String domain = email.substring(separator + 1);
        String maskedLocal = local.length() <= 2
                ? local.charAt(0) + "***"
                : local.substring(0, Math.min(2, local.length())) + "***"
                + local.substring(Math.max(2, local.length() - 2));
        int dot = domain.lastIndexOf('.');
        String domainName = dot > 0 ? domain.substring(0, dot) : domain;
        String suffix = dot > 0 ? domain.substring(dot) : "";
        String maskedDomain = domainName.isEmpty() ? "***" : domainName.charAt(0) + "***";
        return maskedLocal + "@" + maskedDomain + suffix;
    }


    private String enumValue(String value, Set<String> allowed, String field) {
        String normalized = normalizeText(value, 80, field).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw validation(field + "不支持该值");
        }
        return normalized;
    }

    private String normalizeText(String value, int maxLength, String field) {
        String normalized = normalizeOptionalText(value, maxLength, field);
        if (normalized == null) {
            throw validation(field + "不能为空");
        }
        return normalized;
    }

    private String normalizeOptionalText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw validation(field + "格式无效");
        }
        return normalized;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

}
