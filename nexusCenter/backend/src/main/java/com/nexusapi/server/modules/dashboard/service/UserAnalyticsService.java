package com.nexusapi.server.modules.dashboard.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.dashboard.entity.UserAnalyticsRow;
import com.nexusapi.server.modules.dashboard.mapper.UserAnalyticsMapper;
import com.nexusapi.server.modules.dashboard.vo.UserDashboardOverviewResponse;
import com.nexusapi.server.modules.dashboard.vo.UserGroupStatusResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 用户仪表盘和分组状态业务层，统一执行用户隔离、精度计算和响应脱敏。 */
@Service
public class UserAnalyticsService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PRESETS = Set.of("today", "7d", "30d");
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final int RANKING_LIMIT = 6;
    private static final int RECENT_LIMIT = 6;
    private static final int GROUP_SAMPLE_LIMIT = 60;

    private final UserAnalyticsMapper mapper;

    public UserAnalyticsService(UserAnalyticsMapper mapper) {
        this.mapper = mapper;
    }

    /** 返回当前登录用户自己的真实调用统计。 */
    @Transactional(readOnly = true)
    public UserDashboardOverviewResponse dashboard(
            UUID userId,
            String preset,
            Instant customFrom,
            Instant customTo
    ) {
        DashboardWindow window = resolveWindow(preset, customFrom, customTo, Instant.now());
        UserAnalyticsRow.Summary current = mapper.findSummary(userId, window.from(), window.to());
        Duration windowDuration = Duration.between(window.from(), window.to());
        Instant previousTo = window.from();
        Instant previousFrom = previousTo.minus(windowDuration);
        UserAnalyticsRow.Summary previous = mapper.findSummary(userId, previousFrom, previousTo);
        BigDecimal totalBilled = money(current.getBilledAmount());

        UserDashboardOverviewResponse.Summary summary = new UserDashboardOverviewResponse.Summary(
                current.getRequestCount(), current.getSuccessCount(), current.getFailureCount(),
                percentage(current.getSuccessCount(), current.getRequestCount()),
                current.getInputTokens(), current.getOutputTokens(), current.getCachedTokens(),
                percentage(current.getCachedTokens(), current.getInputTokens()), totalBilled,
                averageMoney(totalBilled, current.getRequestCount()),
                average(current.getLatencySumMs(), current.getLatencyCount()),
                current.getLatencyP50Ms(), current.getLatencyP95Ms()
        );

        UserDashboardOverviewResponse.Comparison comparison = new UserDashboardOverviewResponse.Comparison(
                changeRate(BigDecimal.valueOf(current.getRequestCount()),
                        BigDecimal.valueOf(previous.getRequestCount())),
                changeRate(totalBilled, money(previous.getBilledAmount()))
        );

        List<UserDashboardOverviewResponse.TrendPoint> trend = mapper.findTrend(
                userId, window.bucketSize(), window.from(), window.to()
        ).stream().map(row -> new UserDashboardOverviewResponse.TrendPoint(
                row.getBucketStart(), row.getRequestCount(), row.getSuccessCount(), row.getFailureCount(),
                money(row.getBilledAmount()), row.getInputTokens(), row.getOutputTokens(), row.getCachedTokens()
        )).toList();

        List<UserDashboardOverviewResponse.CapabilityItem> capabilities = mapper.findCapabilityDistribution(
                userId, window.from(), window.to()
        ).stream().map(row -> new UserDashboardOverviewResponse.CapabilityItem(
                row.getCapabilityType(), row.getRequestCount(), money(row.getBilledAmount()),
                percentage(money(row.getBilledAmount()), totalBilled)
        )).toList();

        UserDashboardOverviewResponse.Rankings rankings = new UserDashboardOverviewResponse.Rankings(
                rankings(mapper.findModelRanking(userId, window.from(), window.to(), RANKING_LIMIT), totalBilled),
                rankings(mapper.findApiKeyRanking(userId, window.from(), window.to(), RANKING_LIMIT), totalBilled),
                rankings(mapper.findGroupRanking(userId, window.from(), window.to(), RANKING_LIMIT), totalBilled)
        );

        List<UserDashboardOverviewResponse.RecentRequest> recentRequests = mapper.findRecentRequests(
                userId, window.from(), window.to(), RECENT_LIMIT
        ).stream().map(this::recentRequest).toList();

        List<UserDashboardOverviewResponse.ActivityPoint> activity = mapper.findActivityHeatmap(
                userId, window.from(), window.to()
        ).stream().map(row -> new UserDashboardOverviewResponse.ActivityPoint(
                row.getDayOfWeek(), row.getHourOfDay(), row.getRequestCount(), money(row.getBilledAmount())
        )).toList();

        UserAnalyticsRow.Live live = mapper.findLiveMetrics(userId);
        UserDashboardOverviewResponse.LiveMetrics liveMetrics = new UserDashboardOverviewResponse.LiveMetrics(
                live.getRequestCount(), BigDecimal.valueOf(live.getRequestCount())
                        .divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP),
                live.getRequestCount(), live.getInputTokens() + live.getOutputTokens(),
                live.getCachedTokens(), money(live.getBilledAmount())
        );

        return new UserDashboardOverviewResponse(
                window.preset(), window.from(), window.to(), window.bucketSize(),
                mapper.findLatestRequestAt(userId, window.from(), window.to()), summary, comparison,
                trend, capabilities, rankings, recentRequests, activity, liveMetrics
        );
    }

    /**
     * 返回当前用户有权看到的服务分组状态。
     * 可用性只使用该分组最近最多 60 次真实业务请求，不使用接口文档或模拟探测数据。
     */
    @Transactional(readOnly = true)
    public UserGroupStatusResponse groupStatus(UUID userId) {
        Map<String, List<UserGroupStatusResponse.HistoryItem>> histories = new HashMap<>();
        Instant updatedAt = null;
        for (UserAnalyticsRow.GroupHistory row : mapper.findVisibleGroupHistory(userId)) {
            UserGroupStatusResponse.HistoryItem item = new UserGroupStatusResponse.HistoryItem(
                    isSuccessful(row.getStatusCode()) ? "success" : "failed",
                    row.getOccurredAt(), row.getDurationMs(), row.getStatusCode()
            );
            histories.computeIfAbsent(row.getGroupId(), ignored -> new ArrayList<>()).add(item);
            if (updatedAt == null || row.getOccurredAt().isAfter(updatedAt)) {
                updatedAt = row.getOccurredAt();
            }
        }

        List<UserGroupStatusResponse.GroupItem> groups = mapper.findVisibleGroupStatuses(userId).stream()
                .map(row -> groupItem(row, histories.getOrDefault(row.getGroupId(), List.of())))
                .toList();
        return new UserGroupStatusResponse(updatedAt, GROUP_SAMPLE_LIMIT, groups);
    }

    private UserGroupStatusResponse.GroupItem groupItem(
            UserAnalyticsRow.GroupStatus row,
            List<UserGroupStatusResponse.HistoryItem> history
    ) {
        BigDecimal availability = percentage(row.getSuccessCount(), row.getSampleCount());
        String status;
        if (row.getTotalModelCount() == 0) {
            status = "unconfigured";
        } else if (row.getSampleCount() == 0) {
            status = "no_data";
        } else if (row.getSuccessCount() == 0) {
            status = "unavailable";
        } else if (availability.compareTo(new BigDecimal("99.00")) >= 0) {
            status = "normal";
        } else {
            status = "partial";
        }
        return new UserGroupStatusResponse.GroupItem(
                row.getGroupId(), row.getName(), row.getDescription(), money(row.getPriceMultiplier()),
                status, availability, average(row.getLatencySumMs(), row.getLatencyCount()),
                row.getLatencyP95Ms(), row.getAvailableModelCount(), row.getTotalModelCount(),
                row.getSampleCount(), row.getLastRequestAt(), List.copyOf(history)
        );
    }

    private List<UserDashboardOverviewResponse.RankingItem> rankings(
            List<UserAnalyticsRow.Ranking> rows,
            BigDecimal totalBilled
    ) {
        return rows.stream().map(row -> new UserDashboardOverviewResponse.RankingItem(
                row.getDimensionId(), row.getDimensionName(), row.getRequestCount(),
                money(row.getBilledAmount()), percentage(money(row.getBilledAmount()), totalBilled)
        )).toList();
    }

    private UserDashboardOverviewResponse.RecentRequest recentRequest(UserAnalyticsRow.RecentRequest row) {
        boolean successful = isSuccessful(row.getStatusCode());
        return new UserDashboardOverviewResponse.RecentRequest(
                row.getRequestId(), row.getStartedAt(), row.getPublicModel(), row.getApiKeyName(),
                row.getServiceGroupName(), row.getStatusCode(), successful ? "success" : "failed",
                row.getInputTokens(), row.getOutputTokens(), row.getCachedTokens(),
                money(row.getBilledAmount()), row.getDurationMs(), row.isStreaming(), row.getRetryCount(),
                successful ? null : failureReason(row.getPlatformErrorCode(), row.getStatusCode())
        );
    }

    /** 失败原因只使用平台固定文案，历史上游异常原文永远不进入响应。 */
    private String failureReason(String platformErrorCode, Integer statusCode) {
        if (platformErrorCode != null && !platformErrorCode.isBlank()) {
            try {
                return ErrorCode.valueOf(platformErrorCode).defaultMessage();
            } catch (IllegalArgumentException ignored) {
                // 未知历史错误码继续按 HTTP 状态生成固定文案。
            }
        }
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

    private DashboardWindow resolveWindow(String preset, Instant customFrom, Instant customTo, Instant now) {
        if ((customFrom == null) != (customTo == null)) {
            throw validation("自定义时间范围必须同时提供 from 和 to");
        }
        String normalizedPreset = preset == null || preset.isBlank()
                ? "today" : preset.strip().toLowerCase(Locale.ROOT);
        Instant from;
        Instant to;
        if (customFrom != null) {
            normalizedPreset = "custom";
            from = customFrom;
            to = customTo;
        } else {
            if (!PRESETS.contains(normalizedPreset)) {
                throw validation("统计范围只支持 today、7d、30d 或自定义时间");
            }
            ZonedDateTime zonedNow = now.atZone(BUSINESS_ZONE);
            to = now;
            from = switch (normalizedPreset) {
                case "7d" -> zonedNow.toLocalDate().minusDays(6).atStartOfDay(BUSINESS_ZONE).toInstant();
                case "30d" -> zonedNow.toLocalDate().minusDays(29).atStartOfDay(BUSINESS_ZONE).toInstant();
                default -> zonedNow.toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
            };
        }
        if (!from.isBefore(to)) throw validation("统计开始时间必须早于结束时间");
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw validation("单次统计范围不能超过 90 天");
        }
        if (to.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw validation("统计结束时间不能位于未来");
        }
        String bucketSize = Duration.between(from, to).compareTo(Duration.ofDays(2)) <= 0 ? "hour" : "day";
        return new DashboardWindow(normalizedPreset, from, to, bucketSize);
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) return BigDecimal.ZERO.setScale(2);
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal safeDenominator = money(denominator);
        if (safeDenominator.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return money(numerator).multiply(BigDecimal.valueOf(100))
                .divide(safeDenominator, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal changeRate(BigDecimal current, BigDecimal previous) {
        if (previous == null || previous.signum() == 0) return null;
        return current.subtract(previous).multiply(BigDecimal.valueOf(100))
                .divide(previous, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal average(long total, long count) {
        if (count <= 0) return BigDecimal.ZERO.setScale(1);
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    private BigDecimal averageMoney(BigDecimal total, long count) {
        if (count <= 0) return BigDecimal.ZERO.setScale(12);
        return money(total).divide(BigDecimal.valueOf(count), 12, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(12, RoundingMode.HALF_UP);
    }

    private boolean isSuccessful(Integer statusCode) {
        return statusCode != null && statusCode >= 200 && statusCode <= 299;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    private record DashboardWindow(String preset, Instant from, Instant to, String bucketSize) {
    }
}
