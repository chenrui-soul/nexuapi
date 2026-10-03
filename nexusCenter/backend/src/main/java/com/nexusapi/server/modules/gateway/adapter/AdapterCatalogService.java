package com.nexusapi.server.modules.gateway.adapter;

import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 适配器目录管理业务层；管理员维护目录和实现绑定，代码实现仍由后端注册。 */
@Service
public class AdapterCatalogService {
    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]{1,79}$");
    private static final Set<String> CAPABILITIES = Set.of("text", "image", "video");
    private static final Set<String> STATUSES = Set.of("active", "disabled");

    private final AdapterCatalogMapper mapper;
    private final CapabilityAdapterRegistry registry;
    private final AdminAuditService auditService;

    public AdapterCatalogService(
            AdapterCatalogMapper mapper,
            CapabilityAdapterRegistry registry,
            AdminAuditService auditService
    ) {
        this.mapper = mapper;
        this.registry = registry;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminAdapterResponse> list(
            int page, int pageSize, String query, String status, String capabilityType
    ) {
        String normalizedQuery = optional(query, 100, "搜索内容");
        String normalizedStatus = status == null || status.isBlank() ? null : enumValue(status, STATUSES, "适配器状态");
        String normalizedCapability = capabilityType == null || capabilityType.isBlank()
                ? null : enumValue(capabilityType, CAPABILITIES, "能力类型");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findPage(normalizedQuery, normalizedStatus, normalizedCapability, offset, pageSize)
                        .stream().map(this::toResponse).toList(),
                mapper.countPage(normalizedQuery, normalizedStatus, normalizedCapability),
                page,
                pageSize
        );
    }

    @Transactional
    public AdminAdapterResponse create(UUID actorUserId, AdminAdapterRequest request, ClientRequestMetadata metadata) {
        AdapterCatalogRow row = normalize(request, false);
        row.setId(UUID.randomUUID());
        row.setBuiltIn(false);
        if (mapper.countKey(row.getAdapterKey(), null) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "适配器编码已存在", null);
        }
        mapper.insert(row);
        AdminAdapterResponse created = toResponse(require(row.getId()));
        auditService.record(actorUserId, "admin.adapter.create", "gateway_adapter", row.getId(), null, created, metadata);
        return created;
    }

    @Transactional
    public AdminAdapterResponse update(
            UUID actorUserId, UUID id, AdminAdapterRequest request, ClientRequestMetadata metadata
    ) {
        AdapterCatalogRow existing = require(id);
        if (request.version() == null) {
            throw validation("更新适配器必须提供 version");
        }
        AdapterCatalogRow row = normalize(request, true);
        row.setId(id);
        if (!existing.getAdapterKey().equals(row.getAdapterKey())) {
            throw validation("适配器编码创建后不可修改");
        }
        if (existing.isBuiltIn() && !existing.getImplementationKey().equals(row.getImplementationKey())) {
            throw validation("内置适配器不能更换代码实现");
        }
        if (mapper.update(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminAdapterResponse updated = toResponse(require(id));
        auditService.record(actorUserId, "admin.adapter.update", "gateway_adapter", id,
                toResponse(existing), updated, metadata);
        return updated;
    }

    private AdapterCatalogRow normalize(AdminAdapterRequest request, boolean requireVersion) {
        String key = text(request.adapterKey(), 80, "适配器编码").toLowerCase(Locale.ROOT);
        if (!KEY.matcher(key).matches()) {
            throw validation("适配器编码只能使用小写字母、数字和下划线，并以字母开头");
        }
        String capability = enumValue(request.capabilityType(), CAPABILITIES, "能力类型");
        String implementation = text(request.implementationKey(), 80, "代码实现").toLowerCase(Locale.ROOT);
        if (!registry.supportsImplementation(implementation, capability)) {
            throw validation("代码实现不存在，或与当前能力类型不匹配");
        }
        AdapterCatalogRow row = new AdapterCatalogRow();
        row.setAdapterKey(key);
        row.setDisplayName(text(request.displayName(), 120, "适配器名称"));
        row.setCapabilityType(capability);
        row.setImplementationKey(implementation);
        row.setDescription(optional(request.description(), 1000, "适配器说明"));
        row.setStatus(enumValue(request.status(), STATUSES, "适配器状态"));
        row.setVersion(requireVersion ? request.version() : 0L);
        return row;
    }

    private AdapterCatalogRow require(UUID id) {
        AdapterCatalogRow row = mapper.findById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "适配器不存在", null);
        }
        return row;
    }

    private AdminAdapterResponse toResponse(AdapterCatalogRow row) {
        return new AdminAdapterResponse(
                row.getId(), row.getAdapterKey(), row.getDisplayName(), row.getCapabilityType(),
                row.getImplementationKey(), registry.implementationLabel(row.getImplementationKey()),
                row.getDescription(), row.getStatus(), row.isBuiltIn(), row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private String enumValue(String value, Set<String> allowed, String field) {
        String normalized = text(value, 80, field).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw validation(field + "不支持该值");
        return normalized;
    }

    private String text(String value, int maxLength, String field) {
        String normalized = optional(value, maxLength, field);
        if (normalized == null) throw validation(field + "不能为空");
        return normalized;
    }

    private String optional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
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
