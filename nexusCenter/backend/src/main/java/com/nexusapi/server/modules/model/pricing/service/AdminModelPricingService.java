package com.nexusapi.server.modules.model.pricing.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingActivateRequest;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingDeleteRequest;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingRequest;
import com.nexusapi.server.modules.model.pricing.dto.AdminModelPricingSourceModeRequest;
import com.nexusapi.server.modules.model.pricing.entity.ModelContextTierRow;
import com.nexusapi.server.modules.model.pricing.entity.ModelPricingRuleRow;
import com.nexusapi.server.modules.model.pricing.entity.ModelPricingVersionRow;
import com.nexusapi.server.modules.model.pricing.entity.PricingModelStateRow;
import com.nexusapi.server.modules.model.pricing.mapper.AdminModelPricingMapper;
import com.nexusapi.server.modules.model.pricing.model.BillingType;
import com.nexusapi.server.modules.model.pricing.vo.AdminModelPricingResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 发布、查询、回滚和安全清理模型平台销售价格版本。 */
@Service
public class AdminModelPricingService {
    private static final String UPSTREAM_SOURCE = "caicai_market";
    private static final Pattern CONDITION_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    private static final Set<String> SENSITIVE_CONDITION_KEYS = Set.of(
            "authorization", "token", "secret", "password", "api_key", "apikey", "credential"
    );
    private static final Set<String> UNMATCHED_BEHAVIORS = Set.of("base", "reject");
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final AdminModelPricingMapper mapper;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminModelPricingService(
            AdminModelPricingMapper mapper,
            AdminAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminModelPricingResponse get(UUID modelId) {
        PricingModelStateRow state = requireModel(mapper.findModel(modelId));
        return response(state, mapper.findVersions(modelId));
    }

    /** 新版本只插入一次，发布后不可修改；后续调整必须再创建一个版本。 */
    @Transactional
    public AdminModelPricingResponse publish(
            UUID actorUserId,
            UUID modelId,
            AdminModelPricingRequest request,
            ClientRequestMetadata metadata
    ) {
        PricingModelStateRow state = requireModel(mapper.lockModel(modelId));
        assertModelVersion(state, request.modelVersion());
        BillingType billingType = validateRequest(state.capabilityType(), request);
        AdminModelPricingResponse before = response(state, mapper.findVersions(modelId));

        UUID pricingVersionId = UUID.randomUUID();
        ModelPricingVersionRow version = new ModelPricingVersionRow(
                pricingVersionId,
                modelId,
                mapper.nextVersionNo(modelId),
                billingType.code(),
                money(request.unitPrice()),
                money(request.displayOriginalPrice()),
                request.inputTokenRatio(),
                request.outputTokenRatio(),
                request.audioInputTokenRatio(),
                request.audioOutputTokenRatio(),
                request.cachedInputTokenRatio(),
                request.cacheWrite5mTokenRatio(),
                request.cacheWrite1hTokenRatio(),
                normalizeOptional(request.chargeDesc(), 1000, "计费说明"),
                request.contextTierMode(),
                normalizeUnmatchedBehavior(request.unmatchedBehavior()),
                "manual",
                null,
                null,
                normalizeOptional(request.changeNote(), 500, "变更说明"),
                actorUserId,
                null
        );
        mapper.insertVersion(version);
        insertRules(pricingVersionId, request.rules());
        insertContextTiers(pricingVersionId, request.contextTiers());
        if (mapper.activateVersion(modelId, pricingVersionId, request.modelVersion()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }

        AdminModelPricingResponse after = get(modelId);
        auditService.record(actorUserId, "admin.model.pricing.publish", "ai_model", modelId,
                before, after, metadata);
        return after;
    }

    /**
     * 显式切换价格覆盖边界。人工模式保留当前版本但阻止同步覆盖；跟随模式优先立即恢复最近上游版本。
     */
    @Transactional
    public AdminModelPricingResponse updateSourceMode(
            UUID actorUserId,
            UUID modelId,
            AdminModelPricingSourceModeRequest request,
            ClientRequestMetadata metadata
    ) {
        PricingModelStateRow state = requireModel(mapper.lockModel(modelId));
        assertModelVersion(state, request.modelVersion());
        AdminModelPricingResponse before = response(state, mapper.findVersions(modelId));
        if (state.pricingSourceManaged() == request.followUpstream()) {
            return before;
        }

        int changed;
        if (request.followUpstream()) {
            ModelPricingVersionRow latest = mapper.findLatestSourceVersion(modelId, UPSTREAM_SOURCE);
            changed = latest == null
                    ? mapper.enableSourceFollowing(modelId, request.modelVersion())
                    : mapper.followSourceVersion(modelId, latest.id(), request.modelVersion());
        } else {
            changed = mapper.disableSourceFollowing(modelId, request.modelVersion());
        }
        if (changed != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }

        AdminModelPricingResponse after = get(modelId);
        auditService.record(actorUserId, "admin.model.pricing.source-mode.update", "ai_model", modelId,
                before, after, metadata);
        return after;
    }

    /** 激活历史版本只移动当前指针，历史版本、规则和请求快照保持不变。 */
    @Transactional
    public AdminModelPricingResponse activate(
            UUID actorUserId,
            UUID modelId,
            UUID versionId,
            AdminModelPricingActivateRequest request,
            ClientRequestMetadata metadata
    ) {
        PricingModelStateRow state = requireModel(mapper.lockModel(modelId));
        assertModelVersion(state, request.modelVersion());
        ModelPricingVersionRow target = mapper.findVersion(versionId);
        if (target == null || !modelId.equals(target.modelId())) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "价格版本不存在", null);
        }
        BillingType targetType = BillingType.fromCode(target.billingType());
        if (!targetType.supportsCapability(state.capabilityType())) {
            throw validation("历史价格版本与当前模型能力类型不兼容");
        }
        AdminModelPricingResponse before = response(state, mapper.findVersions(modelId));
        if (mapper.activateVersion(modelId, versionId, request.modelVersion()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminModelPricingResponse after = get(modelId);
        auditService.record(actorUserId, "admin.model.pricing.activate", "ai_model", modelId,
                before, after, metadata);
        return after;
    }

    /**
     * 只允许删除未生效且未进入任何请求计费快照的版本。
     * 版本内容仍然不可修改；删除用于清理误发布或从未使用的历史记录。
     */
    @Transactional
    public AdminModelPricingResponse deleteVersion(
            UUID actorUserId,
            UUID modelId,
            UUID versionId,
            AdminModelPricingDeleteRequest request,
            ClientRequestMetadata metadata
    ) {
        PricingModelStateRow state = requireModel(mapper.lockModel(modelId));
        assertModelVersion(state, request.modelVersion());
        ModelPricingVersionRow target = mapper.findVersion(versionId);
        if (target == null || !modelId.equals(target.modelId())) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "价格版本不存在", null);
        }
        if (versionId.equals(state.activePricingVersionId())) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "当前生效的价格版本不能删除", null);
        }
        if (mapper.countBillingReferences(versionId) > 0) {
            throw new BusinessException(
                    ErrorCode.CONFIGURATION_CONFLICT,
                    "该价格版本已用于请求计费，必须保留用于账单追溯",
                    null
            );
        }

        Map<String, Object> before = Map.of(
                "version_id", target.id(),
                "version_no", target.versionNo(),
                "source_type", target.sourceType(),
                "unit_price", target.unitPrice()
        );
        if (mapper.deleteVersion(modelId, versionId) != 1
                || mapper.bumpModelVersion(modelId, request.modelVersion()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }

        AdminModelPricingResponse after = get(modelId);
        auditService.record(
                actorUserId,
                "admin.model.pricing.version.delete",
                "ai_model",
                modelId,
                before,
                Map.of("deleted", true, "model_version", after.modelVersion()),
                metadata
        );
        return after;
    }

    private BillingType validateRequest(String capabilityType, AdminModelPricingRequest request) {
        BillingType billingType = BillingType.fromCode(request.billingType());
        if (!billingType.supportsCapability(capabilityType)) {
            throw validation("该计费类型不适用于当前模型能力");
        }
        if ("audio".equalsIgnoreCase(capabilityType) && billingType == BillingType.VIDEO_SECOND) {
            throw validation("新音频按秒价格必须使用计费类型 6；类型 3 仅用于历史版本兼容");
        }
        if (billingType != BillingType.TOKEN && !request.contextTiers().isEmpty()) {
            throw validation("只有按 Token 计费的模型可以配置长上下文分档");
        }
        if (billingType != BillingType.TOKEN && request.contextTierMode() != 0) {
            throw validation("非 Token 模型的上下文分档模式必须为 0");
        }
        validateRules(capabilityType, request.rules());
        validateTiers(request.contextTiers());
        normalizeUnmatchedBehavior(request.unmatchedBehavior());
        return billingType;
    }

    private void validateRules(
            String capabilityType,
            List<AdminModelPricingRequest.PricingRuleInput> rules
    ) {
        Set<Integer> priorities = new HashSet<>();
        for (AdminModelPricingRequest.PricingRuleInput rule : rules) {
            if (!priorities.add(rule.priority())) {
                throw validation("条件计价规则的 priority 不能重复");
            }
            if (rule.billingType() != null) {
                BillingType ruleType = BillingType.fromCode(rule.billingType());
                if (!ruleType.supportsCapability(capabilityType)) {
                    throw validation("条件规则的计费类型与模型能力不兼容");
                }
                if ("audio".equalsIgnoreCase(capabilityType) && ruleType == BillingType.VIDEO_SECOND) {
                    throw validation("新音频条件规则必须使用计费类型 6；类型 3 仅用于历史版本兼容");
                }
            }
            for (Map.Entry<String, String> condition : rule.matchConditions().entrySet()) {
                String key = condition.getKey() == null ? "" : condition.getKey().strip().toLowerCase(Locale.ROOT);
                if (!CONDITION_KEY.matcher(key).matches() || isSensitiveKey(key)) {
                    throw validation("条件规则包含不允许的参数名");
                }
                normalizeRequired(condition.getValue(), 160, "条件规则参数值");
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        String compact = key.replace("-", "").replace("_", "").replace(".", "");
        return SENSITIVE_CONDITION_KEYS.stream()
                .map(value -> value.replace("_", ""))
                .anyMatch(compact::contains);
    }

    private void validateTiers(List<AdminModelPricingRequest.ContextTierInput> tiers) {
        List<AdminModelPricingRequest.ContextTierInput> sorted = tiers.stream()
                .sorted(Comparator.comparingLong(AdminModelPricingRequest.ContextTierInput::minInputTokens))
                .toList();
        Long previousMax = null;
        boolean openEndedSeen = false;
        for (AdminModelPricingRequest.ContextTierInput tier : sorted) {
            if (openEndedSeen) {
                throw validation("无上限上下文分档必须放在最后");
            }
            if (tier.maxInputTokens() != null && tier.maxInputTokens() <= tier.minInputTokens()) {
                throw validation("上下文分档最大 Token 必须大于最小 Token");
            }
            if (previousMax != null && tier.minInputTokens() < previousMax) {
                throw validation("上下文分档范围不能重叠");
            }
            previousMax = tier.maxInputTokens();
            openEndedSeen = tier.maxInputTokens() == null;
        }
    }

    private void insertRules(UUID versionId, List<AdminModelPricingRequest.PricingRuleInput> rules) {
        for (AdminModelPricingRequest.PricingRuleInput input : rules) {
            Map<String, String> normalized = new LinkedHashMap<>();
            input.matchConditions().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> normalized.put(
                            entry.getKey().strip().toLowerCase(Locale.ROOT),
                            entry.getValue().strip()
                    ));
            mapper.insertRule(new ModelPricingRuleRow(
                    UUID.randomUUID(),
                    versionId,
                    input.priority(),
                    normalizeRequired(input.name(), 120, "规则名称"),
                    toJson(normalized),
                    input.billingType(),
                    input.unitPrice() == null ? null : money(input.unitPrice()),
                    input.priceMultiplier().setScale(10, RoundingMode.UNNECESSARY)
            ));
        }
    }

    private void insertContextTiers(UUID versionId, List<AdminModelPricingRequest.ContextTierInput> tiers) {
        for (AdminModelPricingRequest.ContextTierInput input : tiers) {
            mapper.insertContextTier(new ModelContextTierRow(
                    UUID.randomUUID(),
                    versionId,
                    input.priority(),
                    input.minInputTokens(),
                    input.maxInputTokens(),
                    input.inputRatio(),
                    input.outputRatio(),
                    input.cachedInputRatio(),
                    input.cacheWrite5mRatio(),
                    input.cacheWrite1hRatio()
            ));
        }
    }

    private AdminModelPricingResponse response(
            PricingModelStateRow state,
            List<ModelPricingVersionRow> versions
    ) {
        List<AdminModelPricingResponse.PricingVersion> items = new ArrayList<>();
        for (ModelPricingVersionRow version : versions) {
            items.add(toResponse(version, version.id().equals(state.activePricingVersionId())));
        }
        AdminModelPricingResponse.PricingVersion active = items.stream()
                .filter(AdminModelPricingResponse.PricingVersion::active)
                .findFirst()
                .orElse(null);
        return new AdminModelPricingResponse(
                state.id(), state.activePricingVersionId(),
                state.pricingSourceManaged() ? "follow_upstream" : "manual",
                state.pricingSourceHash(), state.pricingSourceSyncedAt(), state.version(),
                active, List.copyOf(items)
        );
    }

    private AdminModelPricingResponse.PricingVersion toResponse(
            ModelPricingVersionRow version,
            boolean active
    ) {
        List<AdminModelPricingResponse.PricingRule> rules = mapper.findRules(version.id()).stream()
                .map(row -> new AdminModelPricingResponse.PricingRule(
                        row.id(), row.priority(), row.name(), readMap(row.matchConditionsJson()),
                        row.billingType(), row.unitPrice(), row.priceMultiplier()
                ))
                .toList();
        List<AdminModelPricingResponse.ContextTier> tiers = mapper.findContextTiers(version.id()).stream()
                .map(row -> new AdminModelPricingResponse.ContextTier(
                        row.id(), row.priority(), row.minInputTokens(), row.maxInputTokens(),
                        row.inputRatio(), row.outputRatio(), row.cachedInputRatio(),
                        row.cacheWrite5mRatio(), row.cacheWrite1hRatio()
                ))
                .toList();
        return new AdminModelPricingResponse.PricingVersion(
                version.id(), version.versionNo(), version.billingType(),
                BillingType.fromCode(version.billingType()).unit(), version.unitPrice(),
                version.displayOriginalPrice(), version.inputTokenRatio(), version.outputTokenRatio(),
                version.audioInputTokenRatio(), version.audioOutputTokenRatio(),
                version.cachedInputTokenRatio(), version.cacheWrite5mTokenRatio(),
                version.cacheWrite1hTokenRatio(), version.chargeDesc(), version.contextTierMode(),
                version.unmatchedBehavior(), version.sourceType(), version.sourceHash(), version.sourceObservedAt(),
                version.changeNote(), version.createdBy(), version.createdAt(),
                active, rules, tiers
        );
    }

    private PricingModelStateRow requireModel(PricingModelStateRow row) {
        if (row == null) throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND);
        return row;
    }

    private void assertModelVersion(PricingModelStateRow state, long expected) {
        if (state.version() != expected) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
    }

    private String normalizeUnmatchedBehavior(String value) {
        String normalized = normalizeRequired(value, 16, "未命中规则处理方式").toLowerCase(Locale.ROOT);
        if (!UNMATCHED_BEHAVIORS.contains(normalized)) {
            throw validation("未命中规则处理方式只支持 base 或 reject");
        }
        return normalized;
    }

    private BigDecimal money(BigDecimal value) {
        try {
            return value.setScale(12, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw validation("积分价格最多保留 12 位小数");
        }
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

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize pricing conditions", exception);
        }
    }

    private Map<String, String> readMap(String value) {
        try {
            return Map.copyOf(objectMapper.readValue(value, STRING_MAP));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid pricing conditions", exception);
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
