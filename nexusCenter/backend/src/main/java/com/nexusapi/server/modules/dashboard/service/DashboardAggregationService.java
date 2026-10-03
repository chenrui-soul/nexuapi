package com.nexusapi.server.modules.dashboard.service;

import com.nexusapi.server.common.config.DashboardProperties;
import com.nexusapi.server.modules.dashboard.mapper.DashboardAggregationMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/** 将调用事实幂等聚合为小时与日时间桶，不修改任何计费或网关事实记录。 */
@Service
public class DashboardAggregationService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final DashboardAggregationMapper mapper;
    private final DashboardProperties properties;

    public DashboardAggregationService(DashboardAggregationMapper mapper, DashboardProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    /** 调度入口：初次回填 90 天，后续只重算可能出现晚到日志的近期时间窗。 */
    @Transactional
    public boolean refreshScheduled(boolean initialBackfill, Instant now) {
        if (!mapper.tryAggregationLock()) {
            return false;
        }
        int dayLookback = initialBackfill ? properties.backfillDays() : properties.recentDays();
        Instant dayFrom = now.atZone(BUSINESS_ZONE).toLocalDate()
                .minusDays(dayLookback - 1L).atStartOfDay(BUSINESS_ZONE).toInstant();
        Instant hourFrom = now.truncatedTo(ChronoUnit.HOURS).minus(properties.recentHours() - 1L, ChronoUnit.HOURS);
        refreshBucket("day", dayFrom, now);
        refreshBucket("hour", hourFrom, now);
        return true;
    }

    /** 测试与受控维护入口：同一事实区间重复执行不会产生重复计数。 */
    @Transactional
    public boolean refreshWindow(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new IllegalArgumentException("aggregation window must be ordered");
        }
        if (!mapper.tryAggregationLock()) {
            return false;
        }
        refreshBucket("day", from, to);
        refreshBucket("hour", from, to);
        return true;
    }

    private void refreshBucket(String bucketSize, Instant from, Instant to) {
        mapper.upsertRequestAggregates(bucketSize, from, to);
        mapper.upsertAttemptAggregates(bucketSize, from, to);
    }
}

