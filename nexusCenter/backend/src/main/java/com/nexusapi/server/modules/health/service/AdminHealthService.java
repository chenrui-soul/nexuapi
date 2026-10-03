package com.nexusapi.server.modules.health.service;

import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.service.AdminAuditService;
import com.nexusapi.server.modules.auth.service.ClientRequestMetadata;
import com.nexusapi.server.modules.health.entity.ChannelHealthStateRow;
import com.nexusapi.server.modules.health.entity.ChannelHealthTarget;
import com.nexusapi.server.modules.health.entity.HealthAlertRow;
import com.nexusapi.server.modules.health.mapper.AdminHealthMapper;
import com.nexusapi.server.modules.health.mapper.ChannelHealthMapper;
import com.nexusapi.server.modules.health.model.ChannelHealthProbeResult;
import com.nexusapi.server.modules.health.vo.AdminChannelHealthResponse;
import com.nexusapi.server.modules.health.vo.AdminGroupHealthResponse;
import com.nexusapi.server.modules.health.vo.AdminHealthAlertResponse;
import com.nexusapi.server.modules.health.vo.AdminHealthCheckResponse;
import com.nexusapi.server.modules.health.vo.AdminManualProbeResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 管理端健康查询与手动探测业务层。 */
@Service
public class AdminHealthService {
    private static final Set<String> CHANNEL_STATUSES = Set.of("active", "disabled", "degraded");
    private static final Set<String> HEALTH_STATUSES = Set.of("healthy", "degraded", "unavailable", "unconfigured");
    private static final Set<String> TARGET_TYPES = Set.of("channel", "group");
    private static final Set<String> ALERT_STATUSES = Set.of("open", "resolved");

    private final AdminHealthMapper adminMapper;
    private final ChannelHealthMapper channelMapper;
    private final ChannelHealthProbeClient probeClient;
    private final ChannelHealthService channelHealthService;
    private final AdminAuditService auditService;

    public AdminHealthService(
            AdminHealthMapper adminMapper,
            ChannelHealthMapper channelMapper,
            ChannelHealthProbeClient probeClient,
            ChannelHealthService channelHealthService,
            AdminAuditService auditService
    ) {
        this.adminMapper = adminMapper;
        this.channelMapper = channelMapper;
        this.probeClient = probeClient;
        this.channelHealthService = channelHealthService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminChannelHealthResponse> listChannels(
            int page, int pageSize, String query, String status
    ) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedStatus = enumValue(status, CHANNEL_STATUSES, "渠道状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                adminMapper.findChannelPage(normalizedQuery, normalizedStatus, offset, pageSize).stream()
                        .map(row -> new AdminChannelHealthResponse(
                                row.getChannelId(), row.getChannelName(), row.getSupplierName(),
                                row.getChannelStatus(), row.getHealthProbePath(), row.getConsecutiveFailures(),
                                row.getCircuitOpenUntil(), row.getLatestCheckStatus(), row.getLatestLatencyMs(),
                                row.getLatestErrorSummary(), row.getLatestCheckedAt()
                        )).toList(),
                adminMapper.countChannels(normalizedQuery, normalizedStatus), page, pageSize
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminGroupHealthResponse> listGroups(
            int page, int pageSize, String query, String healthStatus
    ) {
        String normalizedQuery = normalizeQuery(query);
        String normalizedStatus = enumValue(healthStatus, HEALTH_STATUSES, "健康状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                adminMapper.findGroupPage(normalizedQuery, normalizedStatus, offset, pageSize).stream()
                        .map(row -> new AdminGroupHealthResponse(
                                row.getGroupId(), row.getGroupCode(), row.getGroupName(),
                                row.getConfigurationStatus(), row.getHealthStatus(), row.getConfiguredRouteCount(),
                                row.getAvailableRouteCount(), row.getLatestCheckedAt()
                        )).toList(),
                adminMapper.countGroups(normalizedQuery, normalizedStatus), page, pageSize
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminHealthCheckResponse> listChecks(
            int page, int pageSize, String targetType, String status
    ) {
        String normalizedTargetType = enumValue(targetType, TARGET_TYPES, "目标类型");
        String normalizedStatus = enumValue(status, HEALTH_STATUSES, "健康状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                adminMapper.findCheckPage(normalizedTargetType, normalizedStatus, offset, pageSize).stream()
                        .map(row -> new AdminHealthCheckResponse(
                                row.getId(), row.getTargetType(), row.getTargetId(), row.getTargetName(),
                                row.getStatus(), row.getLatencyMs(), row.getErrorSummary(), row.getCheckedAt()
                        )).toList(),
                adminMapper.countChecks(normalizedTargetType, normalizedStatus), page, pageSize
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminHealthAlertResponse> listAlerts(int page, int pageSize, String status) {
        String normalizedStatus = enumValue(status, ALERT_STATUSES, "告警状态");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                adminMapper.findAlertPage(normalizedStatus, offset, pageSize).stream()
                        .map(this::toAlertResponse).toList(),
                adminMapper.countAlerts(normalizedStatus), page, pageSize
        );
    }

    /**
     * 网络探测不持有数据库事务。探测完成后状态机和审计分别落库，且响应不返回安全摘要原文。
     */
    public AdminManualProbeResponse probeChannel(
            UUID actorUserId,
            UUID channelId,
            ClientRequestMetadata metadata
    ) {
        ChannelHealthTarget target = channelMapper.findProbeTargetById(channelId);
        if (target == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "渠道不存在或暂不可探测", null);
        }
        if ("disabled".equals(target.getStatus())) {
            throw new BusinessException(ErrorCode.CONFIGURATION_CONFLICT, "停用渠道不能执行健康探测", null);
        }

        ChannelHealthProbeResult result = probeClient.probe(target);
        channelHealthService.recordManualProbeResult(target, result);
        ChannelHealthStateRow state = channelMapper.findState(channelId);
        if (state == null) {
            throw new BusinessException(ErrorCode.CONFIGURATION_NOT_FOUND, "渠道不存在", null);
        }

        Map<String, Object> auditAfter = new LinkedHashMap<>();
        auditAfter.put("outcome", result.outcome().name().toLowerCase(Locale.ROOT));
        auditAfter.put("category", result.category() == null ? "none" : result.category());
        auditAfter.put("latency_ms", result.latencyMs());
        auditAfter.put("channel_status", state.getStatus());
        auditService.record(actorUserId, "admin.channel.health_probe", "channel", channelId,
                Map.of(), auditAfter, metadata);

        return new AdminManualProbeResponse(
                channelId, result.outcome().name().toLowerCase(Locale.ROOT), result.category(), result.latencyMs(),
                state.getStatus(), state.getConsecutiveFailures(), state.getCircuitOpenUntil()
        );
    }

    private AdminHealthAlertResponse toAlertResponse(HealthAlertRow row) {
        return new AdminHealthAlertResponse(
                row.getId(), row.getGroupId(), row.getGroupCode(), row.getGroupName(), row.getAlertType(),
                row.getStatus(), row.getSeverity(), row.getTitle(), row.getSummary(), row.getOccurrenceCount(),
                row.getNotificationCount(), row.getSuppressedCount(), row.getOpenedAt(), row.getLastSeenAt(),
                row.getLastNotifiedAt(), row.getResolvedAt(), row.getUpdatedAt()
        );
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 100 || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "搜索内容格式无效", null);
        }
        return normalized;
    }

    private String enumValue(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "不支持该值", null);
        }
        return normalized;
    }
}
