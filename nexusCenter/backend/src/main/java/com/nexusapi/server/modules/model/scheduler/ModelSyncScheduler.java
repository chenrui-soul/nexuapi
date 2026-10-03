package com.nexusapi.server.modules.model.scheduler;

import com.nexusapi.server.modules.model.service.ModelSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 轮询数据库中的动态开关和计划时间，实际多实例防重由同步服务统一处理。 */
@Component
public class ModelSyncScheduler {
    private static final Logger log = LoggerFactory.getLogger(ModelSyncScheduler.class);

    private final ModelSyncService syncService;

    public ModelSyncScheduler(ModelSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(
            fixedDelayString = "${nexus.model-sync.scheduler-poll-interval:60s}",
            initialDelayString = "${nexus.model-sync.scheduler-initial-delay:30s}"
    )
    public void runScheduledCycle() {
        try {
            syncService.runScheduledIfDue();
        } catch (RuntimeException failure) {
            // 外部响应、数据库异常和 Redis 异常都可能携带内部细节，调度日志只记录异常类型。
            log.warn("model_market_sync_schedule_failed type={}", failure.getClass().getSimpleName());
        }
    }
}
