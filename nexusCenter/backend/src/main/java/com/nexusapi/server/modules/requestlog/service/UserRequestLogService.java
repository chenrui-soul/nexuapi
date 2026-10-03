package com.nexusapi.server.modules.requestlog.service;

import com.nexusapi.server.common.api.PageResponse;
import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.requestlog.entity.UserRequestLogRow;
import com.nexusapi.server.modules.requestlog.mapper.RequestLogMapper;
import com.nexusapi.server.modules.requestlog.vo.UserRequestLogResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 用户控制台调用日志读取服务，集中执行用户隔离、筛选和响应脱敏。 */
@Service
public class UserRequestLogService {
    private static final Set<String> STATUSES = Set.of("success", "failed");
    private static final Set<String> PERIODS = Set.of("today", "24h", "7d", "30d", "custom");
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Duration MAX_RANGE = Duration.ofDays(90);

    private final RequestLogMapper mapper;

    public UserRequestLogService(RequestLogMapper mapper) {
        this.mapper = mapper;
    }

    /** 分页查询当前用户自己的调用日志。 */
    @Transactional(readOnly = true)
    public PageResponse<UserRequestLogResponse> list(
            UUID userId,
            int page,
            int pageSize,
            String query,
            String status,
            String model,
            String period,
            Instant customFrom,
            Instant customTo
    ) {
        String normalizedQuery = normalizeOptional(query, 100, "搜索内容");
        String normalizedStatus = normalizeChoice(status, STATUSES, "请求状态", null);
        String normalizedModel = normalizeOptional(model, 160, "模型名称");
        String normalizedPeriod = normalizeChoice(period, PERIODS, "时间范围", "24h");
        TimeWindow window = resolveWindow(normalizedPeriod, customFrom, customTo, Instant.now());
        int offset = Math.multiplyExact(page - 1, pageSize);
        return new PageResponse<>(
                mapper.findUserPage(
                                userId, normalizedQuery, normalizedStatus, normalizedModel,
                                window.from(), window.to(), offset, pageSize
                        ).stream()
                        .map(this::toResponse)
                        .toList(),
                mapper.countUserPage(
                        userId, normalizedQuery, normalizedStatus, normalizedModel, window.from(), window.to()
                ),
                page,
                pageSize
        );
    }

    /** 按 requestId 查询当前用户自己的单条调用日志。 */
    @Transactional(readOnly = true)
    public UserRequestLogResponse get(UUID userId, String requestId) {
        String normalizedRequestId = normalizeRequired(requestId, 80, "请求 ID");
        UserRequestLogRow row = mapper.findUserByRequestId(userId, normalizedRequestId);
        if (row == null) {
            throw new BusinessException(ErrorCode.REQUEST_LOG_NOT_FOUND);
        }
        return toResponse(row);
    }

    private UserRequestLogResponse toResponse(UserRequestLogRow row) {
        boolean successful = isSuccessful(row.getStatusCode());
        return new UserRequestLogResponse(
                row.getId(), row.getRequestId(), row.getStartedAt(), row.getCompletedAt(), row.getDurationMs(),
                row.getPublicModel(), row.getApiKeyName(), row.getServiceGroupName(), row.getStatusCode(),
                successful ? "success" : "failed", row.getInputTokens(), row.getOutputTokens(),
                row.getCachedTokens(), row.getBilledAmount(), row.isStreaming(), row.getRetryCount(),
                successful ? null : failureReason(row), row.getRequestSummaryJson(), row.getResponseSummaryJson(),
                row.getRequestPayloadSize(), row.getResponsePayloadSize()
        );
    }

    private String failureReason(UserRequestLogRow row) {
        String errorCode = row.getPlatformErrorCode();
        if (errorCode != null && !errorCode.isBlank()) {
            try {
                return ErrorCode.valueOf(errorCode).defaultMessage();
            } catch (IllegalArgumentException ignored) {
                // 历史未知错误码不直接回显，继续按 HTTP 状态生成固定安全文案。
            }
        }
        Integer statusCode = row.getStatusCode();
        if (statusCode == null) return "请求未正常完成";
        return switch (statusCode) {
            case 400, 422 -> "请求参数错误";
            case 401 -> "API 令牌无效或已过期";
            case 402 -> "余额不足";
            case 403 -> "当前请求没有访问权限";
            case 408, 504 -> "上游请求超时";
            case 429 -> "请求超过限制";
            default -> statusCode >= 500 ? "服务暂时不可用" : "请求失败";
        };
    }

    private boolean isSuccessful(Integer statusCode) {
        return statusCode != null && statusCode >= 200 && statusCode <= 299;
    }

    private TimeWindow resolveWindow(String period, Instant customFrom, Instant customTo, Instant now) {
        if ((customFrom == null) != (customTo == null)) {
            throw validation("自定义时间范围必须同时提供 from 和 to");
        }
        if ("custom".equals(period) && customFrom == null) {
            throw validation("自定义时间范围不能为空");
        }
        if (!"custom".equals(period) && customFrom != null) {
            throw validation("仅自定义时间范围可以提供 from 和 to");
        }
        Instant from;
        Instant to;
        if ("custom".equals(period)) {
            from = customFrom;
            to = customTo;
        } else {
            ZonedDateTime businessNow = now.atZone(BUSINESS_ZONE);
            to = now;
            from = switch (period) {
                case "today" -> businessNow.toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
                case "7d" -> now.minus(Duration.ofDays(7));
                case "30d" -> now.minus(Duration.ofDays(30));
                default -> now.minus(Duration.ofHours(24));
            };
        }
        if (!from.isBefore(to)) throw validation("时间范围的开始时间必须早于结束时间");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw validation("自定义时间范围不能超过 90 天");
        }
        return new TimeWindow(from, to);
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    private record TimeWindow(Instant from, Instant to) {}

    private String normalizeChoice(String value, Set<String> allowed, String field, String defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "不支持该值", null);
        }
        return normalized;
    }

    private String normalizeOptional(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) return null;
        return normalizeRequired(value, maxLength, field);
    }

    private String normalizeRequired(String value, int maxLength, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "不能为空", null);
        }
        String normalized = value.strip();
        if (normalized.length() > maxLength || normalized.codePoints().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, field + "格式无效", null);
        }
        return normalized;
    }
}
