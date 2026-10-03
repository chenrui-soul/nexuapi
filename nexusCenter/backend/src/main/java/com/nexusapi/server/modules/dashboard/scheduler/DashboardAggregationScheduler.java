package com.nexusapi.server.modules.dashboard.scheduler;

import com.nexusapi.server.modules.dashboard.service.DashboardAggregationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

/** Wave 8 定时聚合器；异常只影响统计新鲜度，绝不能反向影响 Gateway 主调用链路。 */
@Component
@ConditionalOnProperty(prefix = "nexus.dashboard", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DashboardAggregationScheduler {
    private static final Logger log = LoggerFactory.getLogger(DashboardAggregationScheduler.class);

    private final DashboardAggregationService service;
    private final AtomicBoolean initialBackfillPending = new AtomicBoolean(true);

    public DashboardAggregationScheduler(DashboardAggregationService service) {
        this.service = service;
    }

    @Scheduled(
            fixedDelayString = "${nexus.dashboard.refresh-interval:5m}",
            initialDelayString = "${nexus.dashboard.initial-delay:30s}"
    )
    public void refresh() {
        boolean initial = initialBackfillPending.get();
        try {
            boolean refreshed = service.refreshScheduled(initial, Instant.now());
            if (refreshed && initial) {
                initialBackfillPending.compareAndSet(true, false);
            }
            if (refreshed) {
                log.info("dashboard_aggregation_completed initialBackfill={}", initial);
            }
        } catch (RuntimeException failure) {
            // 数据库异常文本可能携带连接信息或 SQL 参数，因此只记录异常类型。
            log.warn("dashboard_aggregation_failed type={}", failure.getClass().getSimpleName());
        }
    }
}
