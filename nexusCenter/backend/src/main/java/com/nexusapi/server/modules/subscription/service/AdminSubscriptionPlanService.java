package com.nexusapi.server.modules.subscription.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.subscription.dto.AdminSubscriptionPlanArchiveRequest;
import com.nexusapi.server.modules.subscription.dto.AdminSubscriptionPlanRequest;
import com.nexusapi.server.modules.subscription.entity.PlanRow;
import com.nexusapi.server.modules.subscription.mapper.AdminSubscriptionPlanMapper;
import com.nexusapi.server.modules.subscription.vo.AdminSubscriptionPlanOptionsResponse;
import com.nexusapi.server.modules.subscription.vo.AdminSubscriptionPlanResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 管理员套餐维护层；套餐关系与基础信息在同一事务内整体保存。 */
@Service
public class AdminSubscriptionPlanService {
    private static final Pattern CODE = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final Set<String> BILLING_CYCLES = Set.of("monthly", "quarterly", "yearly", "one_time");
    private static final Set<String> STATUSES = Set.of("draft", "active", "archived");
    private static final TypeReference<Map<String, Object>> ENTITLEMENTS = new TypeReference<>() { };

    private final AdminSubscriptionPlanMapper mapper;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminSubscriptionPlanService(
            AdminSubscriptionPlanMapper mapper,
            AdminAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public List<AdminSubscriptionPlanResponse> list() {
        return mapper.findAllPlans().stream().map(this::response).toList();
    }

    @Transactional(readOnly = true)
    public AdminSubscriptionPlanOptionsResponse options() {
        return new AdminSubscriptionPlanOptionsResponse(
                mapper.findServiceGroupOptions(), mapper.findModelOptions()
        );
    }

    @Transactional
    public AdminSubscriptionPlanResponse create(
            UUID actorUserId,
            AdminSubscriptionPlanRequest request,
            ClientRequestMetadata metadata
    ) {
        if (request.version() != null && request.version() != 0) {
            throw validation("新建套餐的版本号必须为 0");
        }
        NormalizedPlan normalized = normalize(request, null);
        assertCodeAvailable(normalized.code(), null);
        lockRelations(normalized);
        PlanRow row = row(UUID.randomUUID(), normalized, 0);
        mapper.insertPlan(row);
        replaceRelations(row.getId(), normalized);
        AdminSubscriptionPlanResponse created = response(require(mapper.findPlan(row.getId())));
        auditService.record(actorUserId, "admin.subscription-plan.create", "subscription_plan", row.getId(),
                Map.of(), created, metadata);
        return created;
    }

    @Transactional
    public AdminSubscriptionPlanResponse update(
            UUID actorUserId,
            UUID planId,
            AdminSubscriptionPlanRequest request,
            ClientRequestMetadata metadata
    ) {
        if (request.version() == null) {
            throw validation("更新套餐必须提供 version");
        }
        PlanRow current = require(mapper.lockPlan(planId));
        if (current.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminSubscriptionPlanResponse before = response(current);
        NormalizedPlan normalized = normalize(request, current.getEntitlementsJson());
        assertCodeAvailable(normalized.code(), planId);
        lockRelations(normalized);
        PlanRow updated = row(planId, normalized, current.getVersion());
        if (mapper.updatePlan(updated) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        replaceRelations(planId, normalized);
        AdminSubscriptionPlanResponse after = response(require(mapper.findPlan(planId)));
        auditService.record(actorUserId, "admin.subscription-plan.update", "subscription_plan", planId,
                before, after, metadata);
        return after;
    }

    /** 归档仅关闭未来销售入口，不删除套餐，也不修改任何已售订阅的权限快照。 */
    @Transactional
    public AdminSubscriptionPlanResponse archive(
            UUID actorUserId,
            UUID planId,
            AdminSubscriptionPlanArchiveRequest request,
            ClientRequestMetadata metadata
    ) {
        PlanRow current = require(mapper.lockPlan(planId));
        if (current.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminSubscriptionPlanResponse before = response(current);
        if (!"archived".equals(current.getStatus()) && mapper.archivePlan(planId, request.version()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminSubscriptionPlanResponse after = response(require(mapper.findPlan(planId)));
        if (!before.status().equals(after.status())) {
            auditService.record(actorUserId, "admin.subscription-plan.archive", "subscription_plan", planId,
                    before, after, metadata);
        }
        return after;
    }

    private NormalizedPlan normalize(AdminSubscriptionPlanRequest request, String currentEntitlementsJson) {
        String code = text(request.code(), 64, "套餐编码").toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw validation("套餐编码只能包含小写字母、数字和连字符，且至少 2 个字符");
        }
        String name = text(request.name(), 120, "套餐名称");
        String description = optionalText(request.description(), 500, "套餐说明");
        String billingCycle = enumValue(request.billingCycle(), BILLING_CYCLES, "计费周期");
        String status = enumValue(request.status(), STATUSES, "套餐状态");
        List<String> features = normalizeFeatures(request.features());
        List<UUID> groupIds = distinctIds(request.serviceGroupIds(), "服务分组");
        List<UUID> modelIds = distinctIds(request.modelIds(), "模型");
        if ("active".equals(status) && (groupIds.isEmpty() || modelIds.isEmpty())) {
            throw validation("启用套餐必须至少配置一个服务分组和一个模型");
        }
        return new NormalizedPlan(
                code, name, description, billingCycle, decimal(request.price(), "套餐价格"),
                decimal(request.includedCredits(), "套餐积分"), request.concurrencyLimit(),
                entitlements(currentEntitlementsJson, features), status, request.displayOrder(),
                request.featured(), groupIds, modelIds
        );
    }

    /** 只替换管理员可维护的 features，其余既有扩展权益保持原样，避免编辑套餐时意外丢字段。 */
    private String entitlements(String currentJson, List<String> features) {
        try {
            Map<String, Object> values = currentJson == null || currentJson.isBlank()
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(objectMapper.readValue(currentJson, ENTITLEMENTS));
            values.put("features", features);
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid plan entitlements", exception);
        }
    }

    private void assertCodeAvailable(String code, UUID excludedId) {
        if (mapper.findPlanIdByCode(code, excludedId) != null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "套餐编码已存在", null);
        }
    }

    /** 锁定关联资源后再整体替换，避免保存期间分组或模型被同步任务改为不可售状态。 */
    private void lockRelations(NormalizedPlan plan) {
        if (!plan.serviceGroupIds().isEmpty()
                && mapper.lockSellableServiceGroups(plan.serviceGroupIds()).size() != plan.serviceGroupIds().size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "存在不可用的服务分组", null);
        }
        if (!plan.modelIds().isEmpty()
                && mapper.lockSellableModels(plan.modelIds()).size() != plan.modelIds().size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "存在不可用或未公开的模型", null);
        }
    }

    /** 仅更新套餐定义关系；subscription_* 快照不在该方法的写入范围内。 */
    private void replaceRelations(UUID planId, NormalizedPlan plan) {
        mapper.deletePlanServiceGroups(planId);
        if (!plan.serviceGroupIds().isEmpty()) {
            mapper.insertPlanServiceGroups(planId, plan.serviceGroupIds());
        }
        mapper.deletePlanModels(planId);
        if (!plan.modelIds().isEmpty()) {
            mapper.insertPlanModels(planId, plan.modelIds());
        }
    }

    private PlanRow row(UUID id, NormalizedPlan normalized, long version) {
        PlanRow row = new PlanRow();
        row.setId(id);
        row.setCode(normalized.code());
        row.setName(normalized.name());
        row.setDescription(normalized.description());
        row.setBillingCycle(normalized.billingCycle());
        row.setPrice(normalized.price());
        row.setIncludedCredits(normalized.includedCredits());
        row.setConcurrencyLimit(normalized.concurrencyLimit());
        row.setEntitlementsJson(normalized.entitlementsJson());
        row.setStatus(normalized.status());
        row.setDisplayOrder(normalized.displayOrder());
        row.setFeatured(normalized.featured());
        row.setVersion(version);
        return row;
    }

    private AdminSubscriptionPlanResponse response(PlanRow row) {
        return new AdminSubscriptionPlanResponse(
                row.getId(), row.getCode(), row.getName(), row.getDescription(), row.getBillingCycle(),
                row.getPrice(), row.getIncludedCredits(), row.getConcurrencyLimit(),
                features(row.getEntitlementsJson()), row.getStatus(), row.getDisplayOrder(), row.isFeatured(),
                mapper.findPlanServiceGroups(row.getId()), mapper.findPlanModels(row.getId()),
                row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private List<String> features(String json) {
        try {
            Object raw = objectMapper.readValue(json, ENTITLEMENTS).get("features");
            if (!(raw instanceof List<?> values)) return List.of();
            ArrayList<String> result = new ArrayList<>();
            values.forEach(value -> { if (value instanceof String text && !text.isBlank()) result.add(text); });
            return List.copyOf(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid plan entitlements", exception);
        }
    }

    private List<String> normalizeFeatures(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) normalized.add(text(value, 120, "套餐权益"));
        if (normalized.size() != values.size()) throw validation("套餐权益不能重复");
        return List.copyOf(normalized);
    }

    private List<UUID> distinctIds(List<UUID> ids, String field) {
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>(ids);
        if (distinct.contains(null) || distinct.size() != ids.size()) throw validation(field + "不能重复或为空");
        return distinct.stream().sorted().toList();
    }

    private BigDecimal decimal(BigDecimal value, String field) {
        try {
            return value.setScale(12, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw validation(field + "最多保留 12 位小数");
        }
    }

    private String enumValue(String value, Set<String> allowed, String field) {
        String normalized = text(value, 24, field).toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) throw validation(field + "不支持该值");
        return normalized;
    }

    private String text(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank() || normalized.length() > maxLength
                || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw validation(field + "格式无效");
        }
        return normalized;
    }

    private String optionalText(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        return text(value, maxLength, field);
    }

    private PlanRow require(PlanRow row) {
        if (row == null) throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "订阅套餐不存在", null);
        return row;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    private record NormalizedPlan(
            String code,
            String name,
            String description,
            String billingCycle,
            BigDecimal price,
            BigDecimal includedCredits,
            Integer concurrencyLimit,
            String entitlementsJson,
            String status,
            int displayOrder,
            boolean featured,
            List<UUID> serviceGroupIds,
            List<UUID> modelIds
    ) {
    }
}
