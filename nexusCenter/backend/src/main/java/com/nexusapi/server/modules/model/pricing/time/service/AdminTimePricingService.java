package com.nexusapi.server.modules.model.pricing.time.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.model.pricing.time.dto.AdminTimePricingDeleteRequest;
import com.nexusapi.server.modules.model.pricing.time.dto.AdminTimePricingRuleRequest;
import com.nexusapi.server.modules.model.pricing.time.dto.AdminTimePricingStatusRequest;
import com.nexusapi.server.modules.model.pricing.time.entity.TimePricingRuleModelRow;
import com.nexusapi.server.modules.model.pricing.time.entity.TimePricingRuleRow;
import com.nexusapi.server.modules.model.pricing.time.mapper.TimePricingMapper;
import com.nexusapi.server.modules.model.pricing.time.vo.AdminTimePricingRuleResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 管理员维护特定模型时段倍率，并在事务内阻止同一模型的启用时段重叠。 */
@Service
public class AdminTimePricingService {
    private final TimePricingMapper mapper;
    private final AdminAuditService auditService;

    public AdminTimePricingService(TimePricingMapper mapper, AdminAuditService auditService) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AdminTimePricingRuleResponse> list() {
        return mapper.findAllRules().stream().map(this::response).toList();
    }

    @Transactional
    public AdminTimePricingRuleResponse create(
            UUID actorUserId,
            AdminTimePricingRuleRequest request,
            ClientRequestMetadata metadata
    ) {
        if (request.version() != 0) {
            throw validation("新建规则的版本号必须为 0");
        }
        NormalizedRule normalized = normalize(request);
        lockAndValidateModels(normalized.modelIds());
        TimePricingRuleRow row = new TimePricingRuleRow(
                UUID.randomUUID(), normalized.name(), normalized.multiplier(), normalized.daysCsv(),
                normalized.startTime(), normalized.endTime(), normalized.enabled(), actorUserId,
                null, null, 0
        );
        assertNoOverlap(row, normalized.modelIds(), null);
        mapper.insertRule(row);
        replaceModels(row.id(), normalized.modelIds());
        AdminTimePricingRuleResponse after = response(requireRule(mapper.findRule(row.id())));
        auditService.record(actorUserId, "admin.billing.time-rule.create", "billing_time_rule", row.id(),
                Map.of(), after, metadata);
        return after;
    }

    @Transactional
    public AdminTimePricingRuleResponse update(
            UUID actorUserId,
            UUID ruleId,
            AdminTimePricingRuleRequest request,
            ClientRequestMetadata metadata
    ) {
        TimePricingRuleRow current = requireRule(mapper.lockRule(ruleId));
        assertVersion(current, request.version());
        AdminTimePricingRuleResponse before = response(current);
        NormalizedRule normalized = normalize(request);
        List<UUID> lockedModelIds = unionModelIds(
                mapper.findRuleModels(ruleId).stream().map(TimePricingRuleModelRow::modelId).toList(),
                normalized.modelIds()
        );
        lockAndValidateModels(lockedModelIds);
        TimePricingRuleRow updated = new TimePricingRuleRow(
                ruleId, normalized.name(), normalized.multiplier(), normalized.daysCsv(),
                normalized.startTime(), normalized.endTime(), normalized.enabled(), current.createdBy(),
                current.createdAt(), current.updatedAt(), current.version()
        );
        assertNoOverlap(updated, normalized.modelIds(), ruleId);
        if (mapper.updateRule(updated) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        replaceModels(ruleId, normalized.modelIds());
        AdminTimePricingRuleResponse after = response(requireRule(mapper.findRule(ruleId)));
        auditService.record(actorUserId, "admin.billing.time-rule.update", "billing_time_rule", ruleId,
                before, after, metadata);
        return after;
    }

    @Transactional
    public AdminTimePricingRuleResponse updateStatus(
            UUID actorUserId,
            UUID ruleId,
            AdminTimePricingStatusRequest request,
            ClientRequestMetadata metadata
    ) {
        TimePricingRuleRow current = requireRule(mapper.lockRule(ruleId));
        assertVersion(current, request.version());
        AdminTimePricingRuleResponse before = response(current);
        if (current.enabled() == request.enabled()) return before;
        List<UUID> modelIds = mapper.findRuleModels(ruleId).stream()
                .map(TimePricingRuleModelRow::modelId)
                .sorted()
                .toList();
        lockAndValidateModels(modelIds);
        if (request.enabled()) {
            TimePricingRuleRow enabled = new TimePricingRuleRow(
                    current.id(), current.name(), current.multiplier(), current.daysOfWeekCsv(),
                    current.startTime(), current.endTime(), true, current.createdBy(),
                    current.createdAt(), current.updatedAt(), current.version()
            );
            assertNoOverlap(enabled, modelIds, ruleId);
        }
        if (mapper.updateStatus(ruleId, request.enabled(), request.version()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminTimePricingRuleResponse after = response(requireRule(mapper.findRule(ruleId)));
        auditService.record(actorUserId, "admin.billing.time-rule.status.update", "billing_time_rule", ruleId,
                before, after, metadata);
        return after;
    }

    @Transactional
    public void delete(
            UUID actorUserId,
            UUID ruleId,
            AdminTimePricingDeleteRequest request,
            ClientRequestMetadata metadata
    ) {
        TimePricingRuleRow current = requireRule(mapper.lockRule(ruleId));
        assertVersion(current, request.version());
        AdminTimePricingRuleResponse before = response(current);
        List<UUID> modelIds = before.models().stream().map(AdminTimePricingRuleResponse.ModelSummary::id).sorted().toList();
        lockAndValidateModels(modelIds);
        if (mapper.deleteRule(ruleId, request.version()) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        auditService.record(actorUserId, "admin.billing.time-rule.delete", "billing_time_rule", ruleId,
                before, Map.of(), metadata);
    }

    private NormalizedRule normalize(AdminTimePricingRuleRequest request) {
        String name = request.name().strip();
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw validation("规则名称格式无效");
        }
        if (request.startTime().equals(request.endTime())) {
            throw validation("开始时间和结束时间不能相同");
        }
        Set<Integer> uniqueDays = new LinkedHashSet<>(request.daysOfWeek());
        if (uniqueDays.size() != request.daysOfWeek().size()) {
            throw validation("适用星期不能重复");
        }
        List<Integer> days = uniqueDays.stream().sorted().toList();
        Set<UUID> uniqueModels = new LinkedHashSet<>(request.modelIds());
        if (uniqueModels.size() != request.modelIds().size()) {
            throw validation("关联模型不能重复");
        }
        List<UUID> models = uniqueModels.stream().sorted().toList();
        return new NormalizedRule(
                name,
                request.multiplier().setScale(10, RoundingMode.UNNECESSARY),
                days.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElseThrow(),
                request.startTime(), request.endTime(), request.enabled(), models
        );
    }

    /** 锁定模型行串行化规则写入，避免两个管理员同时创建相互重叠的启用规则。 */
    private void lockAndValidateModels(List<UUID> modelIds) {
        if (modelIds.isEmpty() || mapper.lockModels(modelIds).size() != modelIds.size()) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "关联模型不存在", null);
        }
    }

    private void assertNoOverlap(TimePricingRuleRow target, List<UUID> modelIds, UUID excludeRuleId) {
        if (!target.enabled()) return;
        for (TimePricingRuleRow existing : mapper.findEnabledRulesForModels(modelIds, excludeRuleId)) {
            if (TimePricingRuntimeService.overlaps(target, existing)) {
                throw new BusinessException(
                        ErrorCode.CONFIGURATION_CONFLICT,
                        "所选模型在相同星期和时段已存在启用规则：“" + existing.name() + "”",
                        null
                );
            }
        }
    }

    private void replaceModels(UUID ruleId, List<UUID> modelIds) {
        mapper.deleteRuleModels(ruleId);
        modelIds.forEach(modelId -> mapper.insertRuleModel(ruleId, modelId));
    }

    private List<UUID> unionModelIds(List<UUID> current, List<UUID> updated) {
        Set<UUID> union = new LinkedHashSet<>(current);
        union.addAll(updated);
        return union.stream().sorted().toList();
    }

    private AdminTimePricingRuleResponse response(TimePricingRuleRow row) {
        List<Integer> days = TimePricingRuntimeService.parseDays(row.daysOfWeekCsv()).stream().sorted().toList();
        List<AdminTimePricingRuleResponse.ModelSummary> models = mapper.findRuleModels(row.id()).stream()
                .map(model -> new AdminTimePricingRuleResponse.ModelSummary(
                        model.modelId(), model.publicName(), model.displayName(), model.capabilityType()
                ))
                .toList();
        return new AdminTimePricingRuleResponse(
                row.id(), row.name(), row.multiplier(), days, row.startTime(), row.endTime(), row.enabled(),
                models, row.createdAt(), row.updatedAt(), row.version()
        );
    }

    private TimePricingRuleRow requireRule(TimePricingRuleRow row) {
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "时段计费规则不存在", null);
        }
        return row;
    }

    private void assertVersion(TimePricingRuleRow row, long version) {
        if (row.version() != version) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    private record NormalizedRule(
            String name,
            BigDecimal multiplier,
            String daysCsv,
            LocalTime startTime,
            LocalTime endTime,
            boolean enabled,
            List<UUID> modelIds
    ) {
    }
}
