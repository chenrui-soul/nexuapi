package com.nexusapi.server.modules.channel.service;

import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.channel.dto.AdminChannelRequest;
import com.nexusapi.server.modules.channel.entity.AdminChannelRow;
import com.nexusapi.server.modules.channel.mapper.AdminChannelMapper;
import com.nexusapi.server.modules.channel.vo.AdminChannelOperationResponse;
import com.nexusapi.server.modules.channel.vo.AdminChannelResponse;
import com.nexusapi.server.modules.gateway.capability.PublicGatewayOperation;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** 供应商上游接口配置的业务层；上游密钥由服务分组单独维护。 */
@Service
public class AdminChannelService {
    private static final Pattern PROVIDER = Pattern.compile("^[a-z][a-z0-9_-]{1,79}$");
    private static final Pattern HEALTH_PROBE_PATH = Pattern.compile("^/[A-Za-z0-9._~!$&'()*+,;=:@/-]*$");
    private static final Set<String> MANUAL_CHANNEL_STATUSES = Set.of("active", "disabled");
    private static final Set<String> ALL_CHANNEL_STATUSES = Set.of("active", "disabled", "degraded");
    private final AdminChannelMapper mapper;
    private final AdminAuditService auditService;

    public AdminChannelService(
            AdminChannelMapper mapper,
            AdminAuditService auditService
    ) {
        this.mapper = mapper;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminChannelResponse> listChannels(
            int page, int pageSize, String query, String status, UUID supplierId
    ) {
        String normalizedQuery = normalizeOptionalText(query, 100, "搜索内容");
        String normalizedStatus = status == null || status.isBlank() ? null : enumValue(status, ALL_CHANNEL_STATUSES, "渠道状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findChannelPage(normalizedQuery, normalizedStatus, supplierId, offset, pageSize)
                        .stream().map(this::toChannelResponse).toList(),
                mapper.countChannels(normalizedQuery, normalizedStatus, supplierId), page, pageSize
        );
    }

    /** 返回程序已实现的能力目录，管理端只能从中选择 operation_code。 */
    public List<AdminChannelOperationResponse> listOperations() {
        return java.util.Arrays.stream(PublicGatewayOperation.values())
                .map(operation -> new AdminChannelOperationResponse(
                        operation.operationCode(), operation.displayName(), operation.capabilityType(),
                        operation.httpMethod().name(), operation.publicPath()
                ))
                .toList();
    }

    @Transactional
    public AdminChannelResponse createChannel(
            UUID actorUserId,
            AdminChannelRequest request,
            ClientRequestMetadata metadata
    ) {
        requireSupplier(request.supplierId());
        AdminChannelRow row = normalizeChannel(request, false);
        row.setId(UUID.randomUUID());
        if (mapper.countChannelName(row.getName(), null) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "渠道名称已存在", null);
        }
        if (request.credential() != null && !request.credential().isBlank()) {
            throw validation("上游 APIKey 必须在服务分组中配置，供应商接口不再保存凭证");
        }
        mapper.insertChannel(row);
        AdminChannelResponse created = toChannelResponse(requireChannel(row.getId()));
        auditService.record(actorUserId, "admin.channel.create", "channel", row.getId(), null, created, metadata);
        return created;
    }

    @Transactional
    public AdminChannelResponse updateChannel(
            UUID actorUserId,
            UUID id,
            AdminChannelRequest request,
            ClientRequestMetadata metadata
    ) {
        AdminChannelRow existing = requireChannel(id);
        if (request.version() == null) {
            throw validation("更新渠道必须提供 version");
        }
        requireSupplier(request.supplierId());
        AdminChannelRow row = normalizeChannel(request, true);
        row.setId(id);
        if (mapper.countChannelName(row.getName(), id) != 0) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "渠道名称已存在", null);
        }
        if (request.credential() != null && !request.credential().isBlank()) {
            throw validation("上游 APIKey 必须在服务分组中配置，供应商接口不再保存凭证");
        }
        if (mapper.updateChannel(row) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_VERSION_CONFLICT);
        }
        AdminChannelResponse updated = toChannelResponse(requireChannel(id));
        auditService.record(actorUserId, "admin.channel.update", "channel", id, toChannelResponse(existing), updated, metadata);
        return updated;
    }

    private AdminChannelRow normalizeChannel(AdminChannelRequest request, boolean requireVersion) {
        String providerType = normalizeText(request.providerType(), 80, "渠道类型").toLowerCase(Locale.ROOT);
        if (!PROVIDER.matcher(providerType).matches()) {
            throw validation("渠道类型格式无效");
        }
        AdminChannelRow row = new AdminChannelRow();
        row.setSupplierId(request.supplierId());
        row.setName(normalizeText(request.name(), 120, "渠道名称"));
        row.setProviderType(providerType);
        PublicGatewayOperation operation = PublicGatewayOperation.fromOperationCode(request.operationCode())
                .orElseThrow(() -> validation("operation_code 不是平台已支持的接口能力"));
        row.setOperationCode(operation.operationCode());
        row.setEndpointType(operation.capabilityType());
        row.setRequestMethod(operation.httpMethod().name());
        row.setBaseUrl(normalizeUrl(request.baseUrl(), false, "上游地址", operation == PublicGatewayOperation.IMAGE_TASK_DETAIL));
        row.setHealthProbePath(normalizeHealthProbePath(request.healthProbePath()));
        row.setProxyUrl(normalizeUrl(request.proxyUrl(), true, "代理地址"));
        row.setStatus(enumValue(request.status(), MANUAL_CHANNEL_STATUSES, "渠道状态"));
        row.setTimeoutMs(request.timeoutMs());
        row.setConcurrencyLimit(request.concurrencyLimit());
        row.setPriority(request.priority());
        row.setWeight(request.weight());
        row.setVersion(requireVersion ? request.version() : 0L);
        return row;
    }

    private AdminChannelRow requireChannel(UUID id) {
        AdminChannelRow row = mapper.findChannelById(id);
        if (row == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "渠道不存在", null);
        }
        return row;
    }

    private void requireSupplier(UUID id) {
        if (mapper.countSupplier(id) != 1) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "供应商不存在", null);
        }
    }

    private AdminChannelResponse toChannelResponse(AdminChannelRow row) {
        return new AdminChannelResponse(
                row.getId(), row.getSupplierId(), row.getSupplierCode(), row.getSupplierName(),
                row.getName(), row.getProviderType(), row.getOperationCode(), row.getEndpointType(), row.getRequestMethod(),
                row.getBaseUrl(), row.getHealthProbePath(),
                row.isCredentialConfigured(), row.getCredentialFingerprint(), row.getCredentialUpdatedAt(),
                row.getProxyUrl(), row.getStatus(), row.getTimeoutMs(), row.getConcurrencyLimit(), row.getPriority(),
                row.getWeight(), row.getConsecutiveFailures(), row.getCircuitOpenUntil(), row.getLastErrorSummary(),
                row.getCreatedAt(), row.getUpdatedAt(), row.getVersion()
        );
    }

    private String normalizeUrl(String value, boolean optional, String field) {
        return normalizeUrl(value, optional, field, false);
    }

    private String normalizeUrl(String value, boolean optional, String field, boolean allowIdTemplate) {
        String normalized = normalizeOptionalText(value, 2_048, field);
        if (normalized == null) {
            if (optional) {
                return null;
            }
            throw validation(field + "不能为空");
        }
        try {
            String template = normalized;
            if (allowIdTemplate) {
                if (template.indexOf('{') >= 0 && !template.contains("{id}")) {
                    throw validation(field + "只支持 {id} 路径占位符");
                }
                template = template.replace("{id}", "__nexus_id__");
            } else if (normalized.indexOf('{') >= 0 || normalized.indexOf('}') >= 0) {
                throw validation(field + "格式无效");
            }
            URI uri = new URI(template);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null) {
                throw validation(field + "必须是无用户信息、查询参数和片段的 HTTP(S) URL");
            }
            String result = uri.normalize().toString();
            return allowIdTemplate ? result.replace("__nexus_id__", "{id}") : result;
        } catch (URISyntaxException exception) {
            throw validation(field + "格式无效");
        }
    }

    /**
     * 探测路径只能解析到管理员已经配置并校验过的同一主机和 API 根路径下。
     * 禁止绝对 URL、查询参数、片段、重复斜杠和路径穿越，避免把健康探测变成 SSRF 入口。
     */
    private String normalizeHealthProbePath(String value) {
        String normalized = normalizeOptionalText(value, 256, "健康探测路径");
        if (normalized == null) {
            return "/models";
        }
        if (!HEALTH_PROBE_PATH.matcher(normalized).matches()
                || normalized.contains("..") || normalized.contains("//")) {
            throw validation("健康探测路径必须是安全的相对路径");
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

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }
}
