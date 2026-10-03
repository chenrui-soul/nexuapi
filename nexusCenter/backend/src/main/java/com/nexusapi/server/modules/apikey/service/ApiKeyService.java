package com.nexusapi.server.modules.apikey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.config.NexusProperties;
import com.nexusapi.server.common.crypto.AesGcmFieldCipher;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.apikey.dto.ApiKeyCreateRequest;
import com.nexusapi.server.modules.apikey.dto.ApiKeyStatusRequest;
import com.nexusapi.server.modules.apikey.dto.ApiKeyUpdateRequest;
import com.nexusapi.server.modules.apikey.entity.ApiKeyRow;
import com.nexusapi.server.modules.apikey.enums.ApiKeyStatus;
import com.nexusapi.server.modules.apikey.mapper.ApiKeyMapper;
import com.nexusapi.server.modules.apikey.support.ApiKeyGenerator;
import com.nexusapi.server.modules.apikey.support.GeneratedApiKey;
import com.nexusapi.server.modules.apikey.vo.ApiKeyCreatedResponse;
import com.nexusapi.server.modules.apikey.vo.ApiKeyItemResponse;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.subscription.service.SubscriptionAccessService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * API 令牌管理模块的业务编排层。
 *
 * <p>负责配置规范化、用户资源隔离、数量限制、乐观锁、状态机、幂等撤销和审计；
 * Controller 不直接访问 Mapper，其他业务模块也不应绕过本服务操作 API 令牌。</p>
 */
@Service
public class ApiKeyService {
    private static final Duration MINIMUM_EXPIRY_LEAD = Duration.ofMinutes(5);
    private static final Pattern IP_LITERAL = Pattern.compile("[0-9A-Fa-f:.]+");
    private static final TypeReference<List<UUID>> UUID_LIST = new TypeReference<>() { };
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };

    private final ApiKeyMapper mapper;
    private final ApiKeyGenerator generator;
    private final ApiKeyRateLimitService rateLimitService;
    private final ApiKeyAuditService auditService;
    private final ObjectMapper objectMapper;
    private final NexusProperties.ApiKey properties;
    private final SubscriptionAccessService subscriptionAccessService;
    private final AesGcmFieldCipher secretCipher;

    public ApiKeyService(
            ApiKeyMapper mapper,
            ApiKeyGenerator generator,
            ApiKeyRateLimitService rateLimitService,
            ApiKeyAuditService auditService,
            ObjectMapper objectMapper,
            NexusProperties nexusProperties,
            SubscriptionAccessService subscriptionAccessService,
            AesGcmFieldCipher secretCipher
    ) {
        this.mapper = mapper;
        this.generator = generator;
        this.rateLimitService = rateLimitService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.properties = nexusProperties.apiKey();
        this.subscriptionAccessService = subscriptionAccessService;
        this.secretCipher = secretCipher;
    }

    /**
     * 读取密钥本身不修改 Key 配置，但会写入 reveal 审计记录，因此事务不能声明为只读。
     */
    @Transactional
    public String reveal(UUID userId, UUID id, ClientRequestMetadata metadata) {
        ApiKeyRow row = requireRow(id, userId);
        if (row.getEncryptedSecret() == null) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
        String secret = secretCipher.decrypt(row.getEncryptedSecret());
        auditService.record(userId, "api_key.reveal", id, null, Map.of("id", id.toString()), metadata);
        return secret;
    }

    @Transactional(readOnly = true)
    public PageResponse<ApiKeyItemResponse> list(
            UUID userId,
            int page,
            int pageSize,
            String query,
            String statusValue
    ) {
        String normalizedQuery = normalizeQuery(query);
        ApiKeyStatus status = ApiKeyStatus.parseFilter(statusValue);
        int offset = (page - 1) * pageSize;
        List<ApiKeyItemResponse> items = mapper.findPage(
                        userId,
                        normalizedQuery,
                        status == null ? null : status.value(),
                        offset,
                        pageSize
                ).stream()
                .map(this::toResponse)
                .toList();
        long total = mapper.countPage(userId, normalizedQuery, status == null ? null : status.value());
        return new PageResponse<>(items, total, page, pageSize);
    }

    @Transactional
    public ApiKeyCreatedResponse create(
            UUID userId,
            ApiKeyCreateRequest request,
            ClientRequestMetadata metadata
    ) {
        // 同时限制用户和来源 IP，避免单个账号或批量代理持续生成密钥。
        rateLimitService.assertCreateAllowed(userId, metadata.ipAddress());

        // 锁定用户行后再统计数量，使同一用户的并发创建请求串行化，防止突破数量上限。
        if (mapper.lockActiveUser(userId) == null) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        if (mapper.countNonRevoked(userId) >= properties.maxPerUser()) {
            throw new BusinessException(ErrorCode.API_KEY_LIMIT_REACHED);
        }

        NormalizedConfiguration configuration = normalizeConfiguration(
                userId,
                request.name(),
                request.serviceGroupId(),
                request.defaultGroupId(),
                request.allowedModelIds(),
                request.allowedGroupIds(),
                request.ipAllowlist(),
                request.rpmLimit(),
                request.tpmLimit(),
                request.concurrencyLimit(),
                request.creditLimit(),
                request.expiresAt()
        );

        // 完整 Secret 仅以 AES-GCM 密文保存，便于用户后续复制且不落明文。
        GeneratedApiKey generated = generator.generate();
        UUID id = UUID.randomUUID();
        mapper.insert(
                id,
                userId,
                configuration.name(),
                generated.prefix(),
                generated.suffix(),
                generated.hash(),
                generated.hashVersion(),
                configuration.serviceGroupId(),
                toJson(configuration.allowedModelIds()),
                toJson(configuration.allowedGroupIds()),
                toJson(configuration.ipAllowlist()),
                secretCipher.encrypt(generated.secret()),
                configuration.rpmLimit(),
                configuration.tpmLimit(),
                configuration.concurrencyLimit(),
                configuration.creditLimit(),
                configuration.expiresAt()
        );

        ApiKeyItemResponse item = toResponse(requireRow(id, userId));
        auditService.record(userId, "api_key.create", id, null, auditView(item), metadata);
        return new ApiKeyCreatedResponse(
                item.id(),
                item.name(),
                generated.secret(),
                item.maskedKey(),
                item.status(),
                item.createdAt(),
                item.version()
        );
    }

    @Transactional
    public ApiKeyItemResponse update(
            UUID userId,
            UUID id,
            ApiKeyUpdateRequest request,
            ClientRequestMetadata metadata
    ) {
        ApiKeyRow current = requireMutableRow(id, userId);

        // PATCH 采用完整配置快照，并用 version 做乐观锁，阻止旧页面覆盖其他会话的新配置。
        if (current.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.API_KEY_VERSION_CONFLICT);
        }
        NormalizedConfiguration configuration = normalizeConfiguration(
                userId,
                request.name(),
                request.serviceGroupId(),
                request.defaultGroupId(),
                request.allowedModelIds(),
                request.allowedGroupIds(),
                request.ipAllowlist(),
                request.rpmLimit(),
                request.tpmLimit(),
                request.concurrencyLimit(),
                request.creditLimit(),
                request.expiresAt()
        );
        ApiKeyItemResponse before = toResponse(current);
        int updated = mapper.updateConfiguration(
                id,
                userId,
                configuration.name(),
                configuration.serviceGroupId(),
                toJson(configuration.allowedModelIds()),
                toJson(configuration.allowedGroupIds()),
                toJson(configuration.ipAllowlist()),
                configuration.rpmLimit(),
                configuration.tpmLimit(),
                configuration.concurrencyLimit(),
                configuration.creditLimit(),
                configuration.expiresAt(),
                request.version()
        );
        if (updated != 1) {
            throw new BusinessException(ErrorCode.API_KEY_VERSION_CONFLICT);
        }
        ApiKeyItemResponse after = toResponse(requireRow(id, userId));
        auditService.record(userId, "api_key.update", id, auditView(before), auditView(after), metadata);
        return after;
    }

    @Transactional
    public ApiKeyItemResponse changeStatus(
            UUID userId,
            UUID id,
            ApiKeyStatusRequest request,
            ClientRequestMetadata metadata
    ) {
        // 状态转换由后端统一校验，不能依赖前端按钮是否可点击来保证安全。
        ApiKeyStatus target = ApiKeyStatus.parseMutable(request.status());
        ApiKeyRow current = requireMutableRow(id, userId);
        String effectiveStatus = effectiveStatus(current);
        if (target.value().equals(effectiveStatus)) {
            return toResponse(current);
        }
        if (current.getVersion() != request.version()) {
            throw new BusinessException(ErrorCode.API_KEY_VERSION_CONFLICT);
        }
        if (target == ApiKeyStatus.ACTIVE && ApiKeyStatus.EXPIRED.value().equals(effectiveStatus)) {
            throw new BusinessException(ErrorCode.API_KEY_STATUS_CONFLICT, "请先设置新的有效期，再启用 API 令牌", null);
        }
        ApiKeyItemResponse before = toResponse(current);
        int updated = mapper.updateStatus(id, userId, target.value(), request.version());
        if (updated != 1) {
            throw new BusinessException(ErrorCode.API_KEY_VERSION_CONFLICT);
        }
        ApiKeyItemResponse after = toResponse(requireRow(id, userId));
        auditService.record(userId, "api_key.status." + target.value(), id, auditView(before), auditView(after), metadata);
        return after;
    }

    @Transactional
    public boolean revoke(UUID userId, UUID id, ClientRequestMetadata metadata) {
        ApiKeyRow current = mapper.findByIdForUser(id, userId);
        if (current == null) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
        if (current.getRevokedAt() != null || ApiKeyStatus.REVOKED.value().equals(current.getStatus())) {
            // 撤销接口保持幂等：重复请求不会重复写审计或返回失败。
            return true;
        }
        ApiKeyItemResponse before = toResponse(current);
        int updated = mapper.revoke(id, userId);
        if (updated == 0) {
            ApiKeyRow latest = mapper.findByIdForUser(id, userId);
            if (latest != null && latest.getRevokedAt() != null) {
                return true;
            }
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
        ApiKeyItemResponse after = toResponse(requireRow(id, userId));
        auditService.record(userId, "api_key.revoke", id, auditView(before), auditView(after), metadata);
        return true;
    }

    private NormalizedConfiguration normalizeConfiguration(
            UUID userId,
            String name,
            UUID serviceGroupId,
            UUID defaultGroupId,
            List<UUID> allowedModelIds,
            List<UUID> allowedGroupIds,
            List<String> ipAllowlist,
            Integer rpmLimit,
            Long tpmLimit,
            Integer concurrencyLimit,
            java.math.BigDecimal creditLimit,
            Instant expiresAt
    ) {
        // 所有写入口都经过同一套规范化，避免创建和编辑采用不同的安全口径。
        String normalizedName = name.strip();
        if (normalizedName.isEmpty() || normalizedName.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "API 令牌名称包含无效字符", null);
        }
        List<UUID> models = distinct(allowedModelIds);
        List<UUID> requestedGroups = distinct(allowedGroupIds);
        UUID selectedGroupId = serviceGroupId != null ? serviceGroupId : defaultGroupId;
        if (selectedGroupId == null && requestedGroups.size() == 1) {
            // 兼容旧客户端：只有一个分组白名单时把它提升为唯一服务分组。
            selectedGroupId = requestedGroups.getFirst();
        }
        if (selectedGroupId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "请选择服务分组", null);
        }
        if (mapper.countSelectableGroups(userId, List.of(selectedGroupId)) != 1) {
            throw new BusinessException(ErrorCode.GROUP_NOT_AVAILABLE);
        }
        SubscriptionAccessService.AccessSnapshot subscriptionAccess = subscriptionAccessService.snapshot(userId);
        subscriptionAccessService.assertGroupAllowed(subscriptionAccess, selectedGroupId);
        subscriptionAccessService.assertModelsAllowed(subscriptionAccess, models);
        if (!requestedGroups.isEmpty()
                && (requestedGroups.size() != 1 || !requestedGroups.contains(selectedGroupId))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "一枚 API 令牌只能绑定一个服务分组", null);
        }
        if (!models.isEmpty() && mapper.countModelsInGroup(selectedGroupId, models) != models.size()) {
            throw new BusinessException(
                    ErrorCode.VALIDATION_ERROR,
                    "模型白名单包含不属于所选服务分组的模型",
                    null
            );
        }
        if (expiresAt != null && expiresAt.isBefore(Instant.now().plus(MINIMUM_EXPIRY_LEAD))) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "有效期至少需要晚于当前时间 5 分钟", null);
        }
        return new NormalizedConfiguration(
                normalizedName,
                selectedGroupId,
                selectedGroupId,
                models,
                List.of(selectedGroupId),
                normalizeCidrs(ipAllowlist),
                rpmLimit,
                tpmLimit,
                concurrencyLimit,
                creditLimit,
                expiresAt
        );
    }

    private List<String> normalizeCidrs(List<String> values) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String candidate = value.strip();
            int separator = candidate.lastIndexOf('/');
            if (separator <= 0 || separator == candidate.length() - 1) {
                throw invalidCidr();
            }
            String addressValue = candidate.substring(0, separator);

            // 只接受 IP 字面量后再交给 InetAddress 解析，禁止把主机名解析变成隐式 DNS 请求。
            if (!IP_LITERAL.matcher(addressValue).matches()) {
                throw invalidCidr();
            }
            try {
                InetAddress address = InetAddress.getByName(addressValue);
                int prefix = Integer.parseInt(candidate.substring(separator + 1));
                int maxPrefix = address.getAddress().length * 8;
                if (prefix < 0 || prefix > maxPrefix) {
                    throw invalidCidr();
                }
                normalized.add(address.getHostAddress() + '/' + prefix);
            } catch (BusinessException exception) {
                throw exception;
            } catch (Exception exception) {
                throw invalidCidr();
            }
        }
        return List.copyOf(normalized);
    }

    private BusinessException invalidCidr() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, "IP 白名单必须使用合法的 IPv4/IPv6 CIDR", null);
    }

    private List<UUID> distinct(List<UUID> values) {
        if (values.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "权限列表不能包含空值", null);
        }
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private ApiKeyRow requireMutableRow(UUID id, UUID userId) {
        ApiKeyRow row = requireRow(id, userId);
        if (row.getRevokedAt() != null || ApiKeyStatus.REVOKED.value().equals(row.getStatus())) {
            throw new BusinessException(ErrorCode.API_KEY_STATUS_CONFLICT, "已撤销的 API 令牌不能修改", null);
        }
        return row;
    }

    private ApiKeyRow requireRow(UUID id, UUID userId) {
        // 资源查询同时限定 id 和 userId，防止仅凭可猜测/泄露的资源 ID 越权访问。
        ApiKeyRow row = mapper.findByIdForUser(id, userId);
        if (row == null) {
            throw new BusinessException(ErrorCode.API_KEY_NOT_FOUND);
        }
        return row;
    }

    private ApiKeyItemResponse toResponse(ApiKeyRow row) {
        // 列表和更新响应只返回脱敏标识，完整 Secret 在创建完成后无法再次读取。
        BigDecimal usedCredits = zeroIfNull(row.getUsedCredits());
        BigDecimal reservedCredits = zeroIfNull(row.getReservedCredits());
        BigDecimal remainingCredits = remainingCredits(row.getCreditLimit(), usedCredits, reservedCredits);
        return new ApiKeyItemResponse(
                row.getId(),
                row.getName(),
                row.getKeyPrefix() + "..." + row.getKeySuffix(),
                effectiveStatus(row),
                row.getServiceGroupId() == null ? row.getDefaultGroupId() : row.getServiceGroupId(),
                row.getServiceGroupName(),
                row.getDefaultGroupId(),
                readUuidList(row.getAllowedModelIdsJson()),
                readUuidList(row.getAllowedGroupIdsJson()),
                readStringList(row.getIpAllowlistJson()),
                row.getRpmLimit(),
                row.getTpmLimit(),
                row.getConcurrencyLimit(),
                row.getCreditLimit(),
                usedCredits,
                reservedCredits,
                remainingCredits,
                row.getExpiresAt(),
                row.getLastUsedAt(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getVersion()
        );
    }

    /**
     * 剩余额度与计费拦截使用相同的暴露口径：已结算 + 冻结中。
     */
    private BigDecimal remainingCredits(
            BigDecimal creditLimit,
            BigDecimal usedCredits,
            BigDecimal reservedCredits
    ) {
        if (creditLimit == null) {
            return null;
        }
        return creditLimit.subtract(usedCredits).subtract(reservedCredits).max(BigDecimal.ZERO);
    }

    /** MyBatis 非用量查询未返回聚合列时仍使用明确的零值。 */
    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String effectiveStatus(ApiKeyRow row) {
        // expired 是根据 expiresAt 动态推导的有效状态，不需要依赖定时任务修改数据库。
        if (!ApiKeyStatus.REVOKED.value().equals(row.getStatus())
                && row.getExpiresAt() != null
                && !row.getExpiresAt().isAfter(Instant.now())) {
            return ApiKeyStatus.EXPIRED.value();
        }
        return row.getStatus();
    }

    private String normalizeQuery(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip();
        if (normalized.length() > 100) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "搜索内容不能超过 100 个字符", null);
        }
        return normalized;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize API key configuration", exception);
        }
    }

    private List<UUID> readUuidList(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, UUID_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid API key UUID configuration", exception);
        }
    }

    private List<String> readStringList(String value) {
        try {
            return List.copyOf(objectMapper.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid API key IP configuration", exception);
        }
    }

    private Map<String, Object> auditView(ApiKeyItemResponse item) {
        // 审计字段使用显式白名单，禁止把 Secret、HMAC 摘要或其他敏感内部字段写入日志。
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.id());
        view.put("name", item.name());
        view.put("masked_key", item.maskedKey());
        view.put("status", item.status());
        view.put("service_group_id", item.serviceGroupId());
        view.put("default_group_id", item.defaultGroupId());
        view.put("allowed_model_ids", item.allowedModelIds());
        view.put("allowed_group_ids", item.allowedGroupIds());
        view.put("ip_allowlist", item.ipAllowlist());
        view.put("rpm_limit", item.rpmLimit());
        view.put("tpm_limit", item.tpmLimit());
        view.put("concurrency_limit", item.concurrencyLimit());
        view.put("credit_limit", item.creditLimit());
        view.put("expires_at", item.expiresAt());
        view.put("version", item.version());
        return view;
    }

    private record NormalizedConfiguration(
            String name,
            UUID serviceGroupId,
            UUID defaultGroupId,
            List<UUID> allowedModelIds,
            List<UUID> allowedGroupIds,
            List<String> ipAllowlist,
            Integer rpmLimit,
            Long tpmLimit,
            Integer concurrencyLimit,
            java.math.BigDecimal creditLimit,
            Instant expiresAt
    ) {
    }
}
