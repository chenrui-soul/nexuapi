package com.nexusapi.server.modules.model.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.gateway.adapter.CapabilityAdapterRegistry;
import com.nexusapi.server.modules.model.dto.AdminModelBatchStatusRequest;
import com.nexusapi.server.modules.model.dto.AdminModelRequest;
import com.nexusapi.server.modules.model.entity.AdminModelRow;
import com.nexusapi.server.modules.model.mapper.AdminModelMapper;
import com.nexusapi.server.modules.model.pricing.model.BillingType;
import com.nexusapi.server.modules.model.vo.AdminModelBatchStatusResponse;
import com.nexusapi.server.modules.model.vo.AdminModelGroupResponse;
import com.nexusapi.server.modules.model.vo.AdminModelInterfaceResponse;
import com.nexusapi.server.modules.model.vo.AdminModelResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 模型目录、用户售价和能力开关的管理员业务层。 */
@Service
public class AdminModelService {
    private static final Pattern PUBLIC_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$");
    private static final Pattern PROVIDER = Pattern.compile("^[a-z][a-z0-9_-]{1,79}$");
    private static final Set<String> CAPABILITIES = Set.of("text", "image", "audio", "video", "embedding", "multimodal");
    private static final Set<String> MODALITIES = Set.of("text", "image", "audio", "video", "file");
    private static final Set<String> PRICE_UNITS = Set.of("million_tokens", "request", "image", "second", "character");
    private static final Set<String> STATUSES = Set.of("active", "disabled", "maintenance");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<List<AdminModelGroupResponse>> MODEL_GROUP_LIST = new TypeReference<>() { };
    private static final TypeReference<List<AdminModelInterfaceResponse>> MODEL_INTERFACE_LIST = new TypeReference<>() { };

    private final AdminModelMapper mapper;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;
    private final CapabilityAdapterRegistry adapterRegistry;

    public AdminModelService(AdminModelMapper mapper, AdminAuditService auditService, ObjectMapper objectMapper,
                             CapabilityAdapterRegistry adapterRegistry) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.adapterRegistry = adapterRegistry;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminModelResponse> list(
            int page, int pageSize, String query, String status, String capabilityType, String provider,
            String serviceGroup
    ) {
        String normalizedQuery = normalizeOptionalText(query, 100, "搜索内容");
        String normalizedStatus = status == null || status.isBlank() ? null : enumValue(status, STATUSES, "模型状态");
        String normalizedCapabilityType = capabilityType == null || capabilityType.isBlank()
                ? null : enumValue(capabilityType, CAPABILITIES, "能力类型");
        String normalizedProvider = normalizeOptionalText(provider, 80, "厂商");
        String normalizedServiceGroup = normalizeOptionalText(serviceGroup, 120, "服务分组");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findPage(normalizedQuery, normalizedStatus, normalizedCapabilityType, normalizedProvider,
                                normalizedServiceGroup, offset, pageSize)
                        .stream().map(this::toResponse).toList(),
                mapper.count(normalizedQuery, normalizedStatus, normalizedCapabilityType, normalizedProvider,
                        normalizedServiceGroup),
                page,
                pageSize
        );
    }

    /**
     * 批量改变模型业务状态。
     *
     * <p>只允许 active/disabled 两个目标状态；重复提交不会重复增加版本号，实际变化的每个模型单独写入审计。</p>
     */
    @Transactional
    public AdminModelBatchStatusResponse batchStatus(
            UUID actorUserId,
            AdminModelBatchStatusRequest request,
            ClientRequestMetadata metadata
    ) {
        String targetStatus = enumValue(request.status(), Set.of("active", "disabled"), "模型批量状态");
        List<UUID> ids = request.modelIds().stream().distinct().toList();
        if (ids.isEmpty()) {
            throw validation("至少选择一个模型");
        }
        List<AdminModelRow> beforeRows = mapper.findByIds(ids);
        if (beforeRows.size() != ids.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "存在不存在的模型配置", null);
        }
        int unchangedCount = (int) beforeRows.stream().filter(row -> targetStatus.equals(row.getStatus())).count();
        int updatedCount = mapper.updateStatusBatch(ids, targetStatus);
        if (updatedCount > 0) {
            Map<UUID, AdminModelRow> afterById = mapper.findByIds(ids).stream()
                    .collect(java.util.stream.Collectors.toMap(AdminModelRow::getId, row -> row, (left, right) -> left, LinkedHashMap::new));
            beforeRows.stream()
                    .filter(row -> !targetStatus.equals(row.getStatus()))
                    .forEach(row -> auditService.record(
                            actorUserId,
                            "admin.model.batch_status." + targetStatus,
                            "ai_model",
                            row.getId(),
                            toResponse(row),
                            toResponse(afterById.get(row.getId())),
                            metadata
                    ));
        }
        return new AdminModelBatchStatusResponse(targetStatus, ids.size(), updatedCount, unchangedCount);
    }

    @Transactional
    public AdminModelResponse create(UUID actorUserId, AdminModelRequest request, ClientRequestMetadata metadata) {
        AdminModelRow row = normalize(request, false);
        row.setId(UUID.randomUUID());
        if (mapper.countByPublicName(row.getPublicName(), null) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "公开模型名称已存在", null);
        }
        mapper.insert(row);
        replaceInterfaces(row.getId(), normalizeInterfaceIds(request.interfaceIds()));
        AdminModelResponse created = toResponse(require(row.getId()));
        auditService.record(actorUserId, "admin.model.create", "ai_model", row.getId(), null, created, metadata);
        return created;
    }

    @Transactional
    public AdminModelResponse update(
            UUID actorUserId,
            UUID id,
            AdminModelRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminModelRow existing = require(id);
        if (request.version() == null) {
            throw validation("更新模型必须提供 version");
        }
        AdminModelRow row = normalize(request, true);
        row.setId(id);
        if (existing.getActivePricingVersionId() != null
                && !BillingType.fromCode(existing.getBillingType()).supportsCapability(row.getCapabilityType())) {
            throw validation("当前生效价格版本与新的模型能力类型不兼容，请先调整计费配置");
        }
        if (mapper.countByPublicName(row.getPublicName(), id) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "公开模型名称已存在", null);
        }
        if (mapper.update(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        // 兼容尚未升级的管理端：缺少 interface_ids 表示保留现有关系，显式空数组才表示清空。
        if (request.interfaceIds() != null) {
            replaceInterfaces(id, normalizeInterfaceIds(request.interfaceIds()));
        }
        AdminModelResponse updated = toResponse(require(id));
        auditService.record(actorUserId, "admin.model.update", "ai_model", id, toResponse(existing), updated, metadata);
        return updated;
    }

    private AdminModelRow normalize(AdminModelRequest request, boolean requireVersion) {
        String publicName = normalizeText(request.publicName(), 160, "公开模型名称");
        if (!PUBLIC_NAME.matcher(publicName).matches()) {
            throw validation("公开模型名称只能包含字母、数字、点、下划线、冒号、斜杠和连字符");
        }
        String provider = normalizeText(request.provider(), 80, "模型提供商").toLowerCase(Locale.ROOT);
        if (!PROVIDER.matcher(provider).matches()) {
            throw validation("模型提供商格式无效");
        }
        AdminModelRow row = new AdminModelRow();
        row.setPublicName(publicName);
        row.setDisplayName(normalizeText(request.displayName(), 160, "显示名称"));
        row.setProvider(provider);
        row.setCapabilityType(enumValue(request.capabilityType(), CAPABILITIES, "模型能力类型"));
        row.setAdapterKey(normalizeAdapterKey(request.adapterKey(), row.getCapabilityType()));
        row.setInputModalitiesJson(toJson(normalizeModalities(request.inputModalities())));
        row.setOutputModalitiesJson(toJson(normalizeModalities(request.outputModalities())));
        row.setContextWindow(request.contextWindow());
        row.setMaxOutputTokens(request.maxOutputTokens());
        row.setSupportsStreaming(request.supportsStreaming());
        row.setSupportsTools(request.supportsTools());
        row.setSupportsStructuredOutput(request.supportsStructuredOutput());
        row.setInputPrice(price(request.inputPrice()));
        row.setOutputPrice(price(request.outputPrice()));
        row.setCachedInputPrice(price(request.cachedInputPrice()));
        row.setPriceUnit(enumValue(request.priceUnit(), PRICE_UNITS, "价格单位"));
        row.setBillingType(legacyBillingType(row.getCapabilityType(), row.getPriceUnit()));
        row.setPublicVisible(request.publicVisible());
        row.setStatus(enumValue(request.status(), STATUSES, "模型状态"));
        row.setVersion(requireVersion ? request.version() : 0L);
        return row;
    }

    /** 接口关系由模型表单统一维护；重复 ID 或不存在的接口都拒绝保存。 */
    private List<UUID> normalizeInterfaceIds(List<UUID> interfaceIds) {
        if (interfaceIds == null) {
            return List.of();
        }
        List<UUID> distinct = interfaceIds.stream().distinct().toList();
        if (distinct.size() != interfaceIds.size()) {
            throw validation("支持接口不能重复");
        }
        if (!distinct.isEmpty() && mapper.countInterfaces(distinct) != distinct.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "存在不存在的接口", null);
        }
        return distinct;
    }

    /** 在同一事务中按模型表单快照重建多对多关系，避免接口页面和模型页面互相覆盖。 */
    private void replaceInterfaces(UUID modelId, List<UUID> interfaceIds) {
        mapper.deleteInterfaces(modelId);
        if (!interfaceIds.isEmpty()) {
            mapper.insertInterfaces(modelId, interfaceIds);
        }
    }

    private List<String> normalizeModalities(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(enumValue(value, MODALITIES, "输入输出模态"));
        }
        return List.copyOf(normalized);
    }

    private BigDecimal price(BigDecimal value) {
        try {
            return value.setScale(10, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw validation("价格最多保留 10 位小数");
        }
    }

    /** 旧模型表单尚未发布价格版本时，为数据库兼容字段推导默认计费类型。 */
    private int legacyBillingType(String capabilityType, String priceUnit) {
        if ("audio".equals(capabilityType) && "second".equals(priceUnit)) {
            return BillingType.AUDIO_SECOND.code();
        }
        if ("audio".equals(capabilityType) && !"character".equals(priceUnit)) {
            return BillingType.CHARACTER.code();
        }
        return switch (priceUnit) {
            case "request" -> BillingType.REQUEST.code();
            case "image" -> BillingType.QUANTITY.code();
            case "second" -> BillingType.VIDEO_SECOND.code();
            case "character" -> BillingType.CHARACTER.code();
            default -> BillingType.TOKEN.code();
        };
    }

    private AdminModelRow require(UUID id) {
        AdminModelRow row = mapper.findById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND);
        }
        return row;
    }

    private AdminModelResponse toResponse(AdminModelRow row) {
        return new AdminModelResponse(
                row.getId(), row.getPublicName(), row.getDisplayName(), row.getProvider(), row.getCapabilityType(), row.getAdapterKey(),
                readStringList(row.getInputModalitiesJson()), readStringList(row.getOutputModalitiesJson()),
                row.getContextWindow(), row.getMaxOutputTokens(), row.isSupportsStreaming(), row.isSupportsTools(),
                row.isSupportsStructuredOutput(), row.getInputPrice(), row.getOutputPrice(), row.getCachedInputPrice(),
                row.getPriceUnit(), row.getBillingType(), row.getUnitPrice(), row.getDisplayOriginalPrice(),
                row.getInputTokenRatio(), row.getOutputTokenRatio(),
                row.getAudioInputTokenRatio(), row.getAudioOutputTokenRatio(),
                row.getCachedInputTokenRatio(), row.getCacheWrite5mTokenRatio(), row.getCacheWrite1hTokenRatio(),
                row.getChargeDesc(), row.getActivePricingVersionId(),
                row.isPublicVisible(), row.getStatus(), row.getSyncSource(), row.getSourceModelKey(),
                row.isSourceManaged(), row.getSourceLastSeenAt(), row.getSourceSyncedAt(),
                readModelGroups(row.getServiceGroupsJson()),
                readModelInterfaces(row.getInterfacesJson()),
                row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private String normalizeAdapterKey(String value, String capabilityType) {
        String defaultKey = switch (capabilityType) {
            case "image" -> "openai_compatible_image";
            case "video" -> "openai_compatible_video";
            default -> "openai_compatible_text";
        };
        String normalized = value == null || value.isBlank() ? defaultKey : normalizeText(value, 80, "协议适配器").toLowerCase(Locale.ROOT);
        if (!adapterRegistry.supportsConfiguredAdapter(normalized, capabilityType)) {
            throw validation("当前模型能力类型不支持该协议适配器");
        }
        return normalized;
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize model configuration", exception);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid model modalities configuration", exception);
        }
    }

    private List<AdminModelGroupResponse> readModelGroups(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, MODEL_GROUP_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid model service group summary", exception);
        }
    }

    private List<AdminModelInterfaceResponse> readModelInterfaces(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, MODEL_INTERFACE_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid model interface summary", exception);
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
