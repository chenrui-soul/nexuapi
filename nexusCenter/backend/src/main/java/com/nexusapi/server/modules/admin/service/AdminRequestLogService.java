package com.nexusapi.server.modules.admin.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.admin.entity.AdminRequestLogRow;
import com.nexusapi.server.modules.admin.mapper.AdminRequestLogMapper;
import com.nexusapi.server.modules.admin.vo.AdminRequestLogResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Set;

@Service
public class AdminRequestLogService {
    private static final Set<String> STATUSES = Set.of("success", "failed");
    private static final Set<String> PERIODS = Set.of("24h", "7d", "30d");
    private final AdminRequestLogMapper mapper;
    private final ObjectMapper objectMapper;

    public AdminRequestLogService(AdminRequestLogMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminRequestLogResponse> list(int page, int pageSize, String query, String status,
                                                       String model, String period) {
        String normalizedQuery = normalize(query, 120, "搜索内容");
        String normalizedModel = normalize(model, 160, "模型");
        String normalizedStatus = normalize(status, 16, "状态");
        String normalizedPeriod = normalize(period, 8, "时间范围");
        validateEnum(normalizedStatus, STATUSES, "状态");
        validateEnum(normalizedPeriod, PERIODS, "时间范围");
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(mapper.findPage(normalizedQuery, normalizedStatus, normalizedModel, normalizedPeriod, offset, pageSize)
                .stream().map(this::toResponse).toList(),
                mapper.countLogs(normalizedQuery, normalizedStatus, normalizedModel, normalizedPeriod), page, pageSize);
    }

    @Transactional(readOnly = true)
    public AdminRequestLogResponse get(String requestId) {
        String normalized = normalize(requestId, 80, "请求 ID");
        if (normalized == null) throw new BusinessException(ErrorCode.REQUEST_LOG_NOT_FOUND, "调用日志不存在", null);
        AdminRequestLogRow row = mapper.findByRequestId(normalized);
        if (row == null) throw new BusinessException(ErrorCode.REQUEST_LOG_NOT_FOUND, "调用日志不存在", null);
        return toResponse(row);
    }

    private AdminRequestLogResponse toResponse(AdminRequestLogRow row) {
        return new AdminRequestLogResponse(row.getId(), row.getRequestId(), row.getUserId(), row.getUserDisplayName(),
                row.getApiKeyName(), row.getServiceGroupName(), row.getSupplierCode(), row.getSupplierName(),
                row.getStartedAt(), row.getCompletedAt(), row.getDurationMs(),
                row.getPublicModel(), row.getStatusCode(), status(row.getStatusCode()), row.getInputTokens(), row.getOutputTokens(),
                row.getCachedTokens(), row.getBilledAmount(), row.isStreaming(), row.getRetryCount(), row.getPlatformErrorCode(),
                parse(row.getRequestSummaryJson()), parse(row.getResponseSummaryJson()), parse(row.getRequestDetailJson()),
                parse(row.getResponseDetailJson()), row.getRequestPayloadSize(), row.getResponsePayloadSize());
    }

    private JsonNode parse(String value) {
        try { return value == null || value.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(value); }
        catch (Exception ignored) { return objectMapper.createObjectNode(); }
    }

    private String status(Integer code) { return code != null && code >= 200 && code < 300 ? "success" : "failed"; }

    private String normalize(String value, int max, String field) {
        if (value == null || value.isBlank() || "all".equalsIgnoreCase(value)) return null;
        String result = value.strip();
        if (result.length() > max || result.codePoints().anyMatch(Character::isISOControl))
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "格式无效", null);
        return result.toLowerCase(Locale.ROOT);
    }

    private void validateEnum(String value, Set<String> allowed, String field) {
        if (value != null && !allowed.contains(value)) throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "不支持该值", null);
    }
}
