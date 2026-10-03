package com.nexusapi.server.modules.dashboard.service;

import com.nexusapi.server.common.error.BusinessException;
import com.nexusapi.server.common.error.ErrorCode;
import com.nexusapi.server.modules.dashboard.entity.DashboardRankingRow;
import com.nexusapi.server.modules.dashboard.entity.DashboardResourceSummaryRow;
import com.nexusapi.server.modules.dashboard.entity.DashboardSummaryRow;
import com.nexusapi.server.modules.dashboard.mapper.AdminDashboardMapper;
import com.nexusapi.server.modules.dashboard.vo.AdminDashboardOverviewResponse;
import com.nexusapi.server.modules.dashboard.vo.AdminResourceOverviewResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** 管理端经营统计业务层，统一校验时间范围并保持金额与百分比精度。 */
@Service
public class AdminDashboardService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final Set<String> PRESETS = Set.of("today", "7d", "30d", "90d");
    private static final Duration MAX_RANGE = Duration.ofDays(90);
    private static final int RANKING_LIMIT = 10;

    private final AdminDashboardMapper mapper;

    public AdminDashboardService(AdminDashboardMapper mapper) {
        this.mapper = mapper;
    }

    /** 返回不受列表分页影响的资源真实总数与启用数。 */
    @Transactional(readOnly = true)
    public AdminResourceOverviewResponse resources() {
        DashboardResourceSummaryRow row = mapper.findResourceSummary();
        return new AdminResourceOverviewResponse(
                new AdminResourceOverviewResponse.ResourceCount(
                        row.getSupplierTotal(), row.getActiveSupplierCount()
                ),
                new AdminResourceOverviewResponse.ResourceCount(
                        row.getModelTotal(), row.getActiveModelCount()
                ),
                new AdminResourceOverviewResponse.ResourceCount(
                        row.getChannelTotal(), row.getActiveChannelCount()
                ),
                row.getOpenAlertCount()
        );
    }

    @Transactional(readOnly = true)
    public AdminDashboardOverviewResponse overview(String preset, Instant customFrom, Instant customTo, String supplierId) {
        DashboardWindow window = resolveWindow(preset, customFrom, customTo, Instant.now());
        String normalizedSupplierId = normalizeSupplierId(supplierId);
        List<String> settlementCurrencies = mapper.findSettlementCurrencies(window.from(), window.to(), normalizedSupplierId);
        if (settlementCurrencies.size() > 1) {
            // P0 没有汇率换算能力，宁可拒绝汇总，也不能向管理员展示错误毛利。
            throw validation("统计区间包含多个供应商结算币种，当前版本不支持跨币种金额汇总");
        }
        String settlementCurrency = settlementCurrencies.isEmpty() ? null : settlementCurrencies.getFirst();
        DashboardSummaryRow row = mapper.findSummary(window.from(), window.to(), normalizedSupplierId);
        AdminDashboardOverviewResponse.Summary summary = toSummary(row);
        List<AdminDashboardOverviewResponse.TrendPoint> trend = mapper.findTrend(
                window.bucketSize(), normalizedSupplierId == null ? "platform" : "supplier",
                normalizedSupplierId == null ? "all" : normalizedSupplierId, window.from(), window.to()
        ).stream().map(item -> new AdminDashboardOverviewResponse.TrendPoint(
                item.getBucketStart(), item.getRequestCount(), item.getSuccessCount(), item.getFailureCount(),
                percentage(item.getSuccessCount(), item.getRequestCount()), money(item.getBilledAmount()),
                money(item.getSupplierCostAmount()), money(item.getGrossMarginAmount()),
                percentage(item.getGrossMarginAmount(), item.getBilledAmount()), item.getLatencyP95Ms()
        )).toList();

        AdminDashboardOverviewResponse.Rankings rankings = new AdminDashboardOverviewResponse.Rankings(
                ranking("supplier", window, normalizedSupplierId), ranking("model", window, normalizedSupplierId),
                ranking("channel", window, normalizedSupplierId), ranking("group", window, normalizedSupplierId)
        );
        List<AdminDashboardOverviewResponse.ErrorDistributionItem> errors = mapper.findErrorDistribution(
                window.from(), window.to(), normalizedSupplierId, 8
        ).stream().map(item -> new AdminDashboardOverviewResponse.ErrorDistributionItem(
                item.getCategory(), item.getOccurrenceCount()
        )).toList();

        return new AdminDashboardOverviewResponse(
                window.preset(), window.from(), window.to(), window.bucketSize(),
                mapper.findLastAggregatedAt(window.bucketSize(), normalizedSupplierId == null ? "platform" : "supplier",
                        normalizedSupplierId == null ? "all" : normalizedSupplierId, window.from(), window.to()),
                settlementCurrency, summary, trend, rankings, errors
        );
    }

    private AdminDashboardOverviewResponse.Summary toSummary(DashboardSummaryRow row) {
        return new AdminDashboardOverviewResponse.Summary(
                row.getRequestCount(), row.getSuccessCount(), row.getFailureCount(), row.getSupplierFailureCount(),
                percentage(row.getSuccessCount(), row.getRequestCount()), row.getInputTokens(), row.getOutputTokens(),
                row.getCachedTokens(), money(row.getBilledAmount()), money(row.getSupplierCostAmount()),
                money(row.getGrossMarginAmount()), percentage(row.getGrossMarginAmount(), row.getBilledAmount()),
                average(row.getLatencySumMs(), row.getRequestCount()), row.getLatencyP95Ms(),
                row.getAttemptCount(), row.getAttemptSuccessCount(), row.getAttemptSupplierFailureCount(),
                percentage(row.getAttemptSuccessCount(), row.getAttemptCount()),
                average(row.getAttemptLatencySumMs(), row.getAttemptCount()), row.getAttemptLatencyP95Ms()
        );
    }

    private List<AdminDashboardOverviewResponse.RankingItem> ranking(String dimension, DashboardWindow window, String supplierId) {
        var rows = supplierId == null
                ? mapper.findRanking(dimension, window.bucketSize(), window.from(), window.to(), RANKING_LIMIT)
                : mapper.findRankingBySupplier(dimension, window.from(), window.to(), supplierId, RANKING_LIMIT);
        return rows
                .stream().map(this::toRanking).toList();
    }

    private String normalizeSupplierId(String supplierId) {
        if (supplierId == null || supplierId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(supplierId.strip()).toString();
        } catch (IllegalArgumentException ex) {
            throw validation("供应商标识无效");
        }
    }

    private AdminDashboardOverviewResponse.RankingItem toRanking(DashboardRankingRow row) {
        return new AdminDashboardOverviewResponse.RankingItem(
                row.getDimensionId(), row.getDimensionName(), row.getRequestCount(), row.getSuccessCount(),
                row.getFailureCount(), percentage(row.getSuccessCount(), row.getRequestCount()),
                row.getInputTokens(), row.getOutputTokens(), row.getCachedTokens(), money(row.getBilledAmount()),
                money(row.getSupplierCostAmount()), money(row.getGrossMarginAmount()),
                percentage(row.getGrossMarginAmount(), row.getBilledAmount()),
                average(row.getLatencySumMs(), row.getRequestCount()), row.getAttemptCount(),
                row.getAttemptSuccessCount(), row.getAttemptSupplierFailureCount(),
                percentage(row.getAttemptSuccessCount(), row.getAttemptCount())
        );
    }

    private DashboardWindow resolveWindow(String preset, Instant customFrom, Instant customTo, Instant now) {
        if ((customFrom == null) != (customTo == null)) {
            throw validation("自定义时间范围必须同时提供 from 和 to");
        }

        String normalizedPreset = preset == null || preset.isBlank()
                ? "7d" : preset.strip().toLowerCase(Locale.ROOT);
        Instant from;
        Instant to;
        if (customFrom != null) {
            normalizedPreset = "custom";
            from = customFrom;
            to = customTo;
        } else {
            if (!PRESETS.contains(normalizedPreset)) {
                throw validation("统计范围只支持 today、7d、30d、90d 或自定义时间");
            }
            ZonedDateTime zonedNow = now.atZone(BUSINESS_ZONE);
            to = now;
            from = switch (normalizedPreset) {
                case "today" -> zonedNow.toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
                case "30d" -> zonedNow.toLocalDate().minusDays(29).atStartOfDay(BUSINESS_ZONE).toInstant();
                case "90d" -> zonedNow.toLocalDate().minusDays(89).atStartOfDay(BUSINESS_ZONE).toInstant();
                default -> zonedNow.toLocalDate().minusDays(6).atStartOfDay(BUSINESS_ZONE).toInstant();
            };
        }

        if (!from.isBefore(to)) {
            throw validation("统计开始时间必须早于结束时间");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw validation("单次统计范围不能超过 90 天");
        }
        if (to.isAfter(now.plus(Duration.ofMinutes(5)))) {
            throw validation("统计结束时间不能位于未来");
        }

        String bucketSize = Duration.between(from, to).compareTo(Duration.ofDays(2)) <= 0 ? "hour" : "day";
        Instant alignedFrom = alignDown(from, bucketSize);
        Instant alignedTo = alignUp(to, bucketSize);
        return new DashboardWindow(normalizedPreset, alignedFrom, alignedTo, bucketSize);
    }

    private Instant alignDown(Instant value, String bucketSize) {
        ZonedDateTime zoned = value.atZone(BUSINESS_ZONE);
        return "hour".equals(bucketSize)
                ? zoned.truncatedTo(ChronoUnit.HOURS).toInstant()
                : zoned.toLocalDate().atStartOfDay(BUSINESS_ZONE).toInstant();
    }

    private Instant alignUp(Instant value, String bucketSize) {
        Instant down = alignDown(value, bucketSize);
        if (down.equals(value)) {
            return value;
        }
        return "hour".equals(bucketSize)
                ? down.plus(1, ChronoUnit.HOURS)
                : down.atZone(BUSINESS_ZONE).plusDays(1).toInstant();
    }

    private BigDecimal percentage(long numerator, long denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        BigDecimal safeDenominator = safe(denominator);
        if (safeDenominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return safe(numerator).multiply(BigDecimal.valueOf(100))
                .divide(safeDenominator, 2, RoundingMode.HALF_UP);
    }

    private BigDecimal average(long total, long count) {
        if (count <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(total).divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return safe(value).setScale(12, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BusinessException validation(String message) {
        return new BusinessException(ErrorCode.VALIDATION_ERROR, message, null);
    }

    private record DashboardWindow(String preset, Instant from, Instant to, String bucketSize) {
    }
}
