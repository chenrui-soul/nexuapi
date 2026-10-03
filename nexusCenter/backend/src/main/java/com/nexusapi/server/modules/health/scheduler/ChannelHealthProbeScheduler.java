package com.nexusapi.server.modules.health.scheduler;

import com.nexusapi.server.common.config.HealthProperties;
import com.nexusapi.server.modules.health.entity.ChannelHealthTarget;
import com.nexusapi.server.modules.health.model.ChannelHealthProbeResult;
import com.nexusapi.server.modules.health.service.ChannelHealthProbeClient;
import com.nexusapi.server.modules.health.service.ChannelHealthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 多实例安全的渠道健康调度器；Redis 不可用时跳过本轮，避免重复探测并发累加失败次数。 */
@Component
@ConditionalOnProperty(prefix = "nexus.health", name = "enabled", havingValue = "true")
public class ChannelHealthProbeScheduler {
    private static final Logger log = LoggerFactory.getLogger(ChannelHealthProbeScheduler.class);
    private static final String LOCK_KEY = "nexus:health:probe:lock";
    private static final DefaultRedisScript<Long> RELEASE_LOCK = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ChannelHealthService healthService;
    private final ChannelHealthProbeClient probeClient;
    private final HealthProperties properties;

    public ChannelHealthProbeScheduler(
            StringRedisTemplate redis,
            ChannelHealthService healthService,
            ChannelHealthProbeClient probeClient,
            HealthProperties properties
    ) {
        this.redis = redis;
        this.healthService = healthService;
        this.probeClient = probeClient;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${nexus.health.probe-interval:60s}",
            initialDelayString = "${nexus.health.initial-delay:15s}"
    )
    public void runProbeCycle() {
        String owner = UUID.randomUUID().toString();
        if (!acquireLock(owner)) {
            return;
        }
        try {
            List<ChannelHealthTarget> targets = healthService.findDueProbeTargets();
            for (ChannelHealthTarget target : targets) {
                probeOne(target);
            }
        } catch (RuntimeException batchFailure) {
            // 禁止输出异常 message，数据库或 Redis 驱动文本可能携带连接信息。
            log.warn("channel_health_probe_batch_failed type={}", batchFailure.getClass().getSimpleName());
        } finally {
            releaseLock(owner);
        }
    }

    private void probeOne(ChannelHealthTarget target) {
        try {
            ChannelHealthProbeResult result = probeClient.probe(target);
            healthService.recordProbeResult(target, result);
            log.info(
                    "channel_health_probe channelId={} outcome={} category={}",
                    target.getChannelId(), result.outcome(), result.category()
            );
        } catch (RuntimeException probeFailure) {
            // 单渠道平台异常不能中断整批，也不能被错误归责给供应商。
            log.warn(
                    "channel_health_probe_internal_failure channelId={} type={}",
                    target.getChannelId(), probeFailure.getClass().getSimpleName()
            );
        }
    }

    private boolean acquireLock(String owner) {
        try {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, owner, properties.lockTtl()));
        } catch (RuntimeException redisFailure) {
            log.warn("channel_health_probe_lock_unavailable type={}", redisFailure.getClass().getSimpleName());
            return false;
        }
    }

    private void releaseLock(String owner) {
        try {
            redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), owner);
        } catch (RuntimeException redisFailure) {
            // 锁带 TTL；释放失败不会永久阻塞下一轮，也不能影响已经完成的状态写入。
            log.warn("channel_health_probe_unlock_failed type={}", redisFailure.getClass().getSimpleName());
        }
    }
}
