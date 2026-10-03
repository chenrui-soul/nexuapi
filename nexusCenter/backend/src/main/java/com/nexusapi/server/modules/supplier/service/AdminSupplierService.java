package com.nexusapi.server.modules.supplier.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.supplier.dto.AdminSupplierRequest;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierChannelDetailRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierFinancialSummaryRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierResourceSummaryRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierRow;
import com.nexusapi.server.modules.supplier.mapper.AdminSupplierMapper;
import com.nexusapi.server.modules.supplier.vo.AdminSupplierDetailResponse;
import com.nexusapi.server.modules.supplier.vo.AdminSupplierResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.regex.Pattern;

/** 供应商合作状态、结算口径和非敏感元数据的业务层。 */
@Service
public class AdminSupplierService {
    private static final Pattern CODE = Pattern.compile("^[a-z][a-z0-9_-]{1,63}$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final Set<String> TYPES = Set.of("direct", "reseller", "aggregator", "other");
    private static final Set<String> STATUSES = Set.of("active", "disabled", "suspended", "terminated");
    private static final Set<String> HEALTH_STATUSES = Set.of("healthy", "degraded", "unavailable", "unconfigured");
    private static final Set<String> BILLING_MODES = Set.of("prepaid", "postpaid", "monthly_settlement", "other");
    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "authorization", "credential", "secret", "token", "accesstoken", "refreshtoken",
            "apikey", "password", "bankaccount", "cardnumber"
    );
    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final int MAX_METADATA_JSON_LENGTH = 8_192;
    private static final int DETAIL_CHANNEL_LIMIT = 50;
    private static final int DETAIL_MODEL_LIMIT = 100;
    private static final int DETAIL_PERIOD_DAYS = 30;
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final AdminSupplierMapper mapper;
    private final AdminAuditService auditService;
    private final ObjectMapper objectMapper;

    public AdminSupplierService(
            AdminSupplierMapper mapper,
            AdminAuditService auditService,
            ObjectMapper objectMapper
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminSupplierResponse> list(
            int page, int pageSize, String query, String status, String healthStatus
    ) {
        String normalizedQuery = normalizeOptionalText(query, 100, "搜索内容");
        String normalizedStatus = optionalEnum(status, STATUSES, "合作状态");
        String normalizedHealth = optionalEnum(healthStatus, HEALTH_STATUSES, "健康状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findPage(normalizedQuery, normalizedStatus, normalizedHealth, offset, pageSize)
                        .stream().map(this::toResponse).toList(),
                mapper.countPage(normalizedQuery, normalizedStatus, normalizedHealth), page, pageSize
        );
    }

    /** 聚合供应商的商业主体、资源关系、资金和稳定性信息。 */
    @Transactional(readOnly = true)
    public AdminSupplierDetailResponse detail(UUID id) {
        AdminSupplierResponse supplier = toResponse(require(id));
        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofDays(DETAIL_PERIOD_DAYS));
        AdminSupplierResourceSummaryRow resources = mapper.findResourceSummary(id);
        AdminSupplierFinancialSummaryRow financial = mapper.findFinancialSummary(id, from, to);

        return new AdminSupplierDetailResponse(
                supplier,
                new AdminSupplierDetailResponse.Period(from, to, DETAIL_PERIOD_DAYS),
                new AdminSupplierDetailResponse.Summary(
                        resources.getChannelCount(), resources.getAvailableChannelCount(),
                        resources.getModelCount(), resources.getActiveModelCount(),
                        financial.getRequestCount(), financial.getSuccessCount(),
                        percentage(financial.getSuccessCount(), financial.getRequestCount()),
                        decimal(financial.getBilledAmount()), decimal(financial.getSupplierCostAmount()),
                        decimal(financial.getGrossMarginAmount()), financial.getAttemptCount(),
                        financial.getAttemptSuccessCount(),
                        percentage(financial.getAttemptSuccessCount(), financial.getAttemptCount()),
                        financial.getAttemptSupplierFailureCount()
                ),
                mapper.findDetailChannels(id, DETAIL_CHANNEL_LIMIT).stream().map(this::toChannel).toList(),
                mapper.findDetailModels(id, DETAIL_MODEL_LIMIT).stream().map(row ->
                        new AdminSupplierDetailResponse.ModelMapping(
                                row.getMappingId(), row.getModelId(), row.getPublicName(), row.getDisplayName(),
                                row.getUpstreamModel(), row.getChannelId(), row.getChannelName(),
                                decimal(row.getCostInputPrice()), decimal(row.getCostCachedInputPrice()),
                                decimal(row.getCostOutputPrice()), row.getStatus(), row.getUpdatedAt()
                        )).toList()
        );
    }

    @Transactional
    public AdminSupplierResponse create(
            UUID actorUserId,
            AdminSupplierRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminSupplierRow row = normalize(request, false);
        row.setId(UUID.randomUUID());
        assertUnique(row, null);
        mapper.insert(row);
        AdminSupplierResponse created = toResponse(require(row.getId()));
        auditService.record(actorUserId, "admin.supplier.create", "supplier", row.getId(), null, created, metadata);
        return created;
    }

    @Transactional
    public AdminSupplierResponse update(
            UUID actorUserId,
            UUID id,
            AdminSupplierRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminSupplierRow existing = require(id);
        if (request.version() == null) {
            throw validation("更新供应商必须提供 version");
        }
        AdminSupplierRow row = normalize(request, true);
        row.setId(id);
        // terminated 是合同审计终态，只允许修改展示和非敏感元数据，不能恢复成可路由状态。
        if ("terminated".equals(existing.getStatus()) && !"terminated".equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "已终止供应商不能重新激活", null);
        }
        assertUnique(row, id);
        if (mapper.update(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminSupplierResponse updated = toResponse(require(id));
        auditService.record(actorUserId, "admin.supplier.update", "supplier", id,
                toResponse(existing), updated, metadata);
        return updated;
    }

    private AdminSupplierRow normalize(AdminSupplierRequest request, boolean requireVersion) {
        String code = normalizeText(request.code(), 64, "供应商编码").toLowerCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw validation("供应商编码格式无效");
        }
        String status = enumValue(request.status(), STATUSES, "合作状态");
        String reason = normalizeOptionalText(request.disabledReason(), 500, "状态原因");
        if (!"active".equals(status) && reason == null) {
            throw validation("非 active 供应商必须填写状态原因");
        }
        String currency = normalizeText(request.settlementCurrency(), 3, "结算币种").toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(currency).matches()) {
            throw validation("结算币种必须是三位大写代码");
        }
        AdminSupplierRow row = new AdminSupplierRow();
        row.setCode(code);
        row.setName(normalizeText(request.name(), 120, "供应商名称"));
        row.setSupplierType(enumValue(request.supplierType(), TYPES, "供应商类型"));
        row.setStatus(status);
        row.setBillingMode(enumValue(request.billingMode(), BILLING_MODES, "结算方式"));
        row.setSettlementCurrency(currency);
        row.setDisabledReason("active".equals(status) ? null : reason);
        row.setMetadataJson(toMetadataJson(request.metadata()));
        row.setVersion(requireVersion ? request.version() : 0L);
        return row;
    }

    private void assertUnique(AdminSupplierRow row, UUID excludedId) {
        if (mapper.countIdentity(row.getCode(), row.getName(), excludedId) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "供应商编码或名称已存在", null);
        }
    }

    private AdminSupplierRow require(UUID id) {
        AdminSupplierRow row = mapper.findById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "供应商不存在", null);
        }
        return row;
    }

    private AdminSupplierResponse toResponse(AdminSupplierRow row) {
        return new AdminSupplierResponse(
                row.getId(), row.getCode(), row.getName(), row.getSupplierType(), row.getStatus(),
                row.getHealthStatus(), row.getBillingMode(), row.getSettlementCurrency(),
                row.getDisabledReason(), row.getDisabledAt(), row.getLastHealthCheckedAt(),
                readMetadata(row.getMetadataJson()), row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private AdminSupplierDetailResponse.Channel toChannel(AdminSupplierChannelDetailRow row) {
        return new AdminSupplierDetailResponse.Channel(
                row.getId(), row.getName(), row.getProviderType(), baseUrlOrigin(row.getBaseUrl()), row.getStatus(),
                row.getConsecutiveFailures(), row.getCircuitOpenUntil(), row.getMappingCount(),
                row.getLastAttemptOutcome(), row.getLastErrorCategory(), row.getLastAttemptAt()
        );
    }

    /** 只保留协议、主机和端口，主动移除路径、查询参数及可能存在的用户信息。 */
    private String baseUrlOrigin(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "已配置";
            }
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + uri.getHost()
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (IllegalArgumentException exception) {
            return "已配置";
        }
    }

    private String decimal(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private double percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return 0D;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP)
                .doubleValue();
    }

    private String toMetadataJson(Map<String, Object> metadata) {
        assertSafeMetadata(metadata);
        try {
            String json = objectMapper.writeValueAsString(metadata);
            if (json.length() > MAX_METADATA_JSON_LENGTH) {
                throw validation("供应商元数据过大");
            }
            return json;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize supplier metadata", exception);
        }
    }

    private void assertSafeMetadata(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                // 移除所有分隔符并检查前后缀，防止 x-api-key、access.token.value 等变体绕过敏感字段拦截。
                if (isSensitiveKey(String.valueOf(entry.getKey()))) {
                    throw validation("供应商元数据不能包含凭证或结算秘密字段");
                }
                assertSafeMetadata(entry.getValue());
            }
        } else if (value instanceof Collection<?> collection) {
            collection.forEach(this::assertSafeMetadata);
        } else if (value != null && value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int index = 0; index < length; index++) {
                assertSafeMetadata(java.lang.reflect.Array.get(value, index));
            }
        }
    }

    private boolean isSensitiveKey(String key) {
        String normalized = NON_ALPHANUMERIC.matcher(key.toLowerCase(Locale.ROOT)).replaceAll("");
        return SENSITIVE_KEYS.stream().anyMatch(marker ->
                normalized.equals(marker) || normalized.startsWith(marker) || normalized.endsWith(marker)
        );
    }

    private Map<String, Object> readMetadata(String value) {
        try {
            return Collections.unmodifiableMap(new LinkedHashMap<>(objectMapper.readValue(value, OBJECT_MAP)));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid supplier metadata", exception);
        }
    }

    private String optionalEnum(String value, Set<String> allowed, String field) {
        return value == null || value.isBlank() ? null : enumValue(value, allowed, field);
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
