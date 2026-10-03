package com.nexusapi.server.modules.health.service;

import com.nexusapi.server.common.config.HealthProperties;
import com.nexusapi.server.modules.health.entity.ChannelHealthStateRow;
import com.nexusapi.server.modules.health.entity.ChannelHealthTarget;
import com.nexusapi.server.modules.health.event.ChannelHealthChangedEvent;
import com.nexusapi.server.modules.health.mapper.ChannelHealthMapper;
import com.nexusapi.server.modules.health.model.ChannelHealthProbeResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 渠道健康状态机。
 *
 * <p>它只接受固定错误分类，并在事务内完成渠道状态、探测明细和供应商聚合，
 * 防止敏感上游正文进入数据库，也防止状态更新一半成功、一半失败。</p>
 */
@Service
public class ChannelHealthService {
    private static final Set<String> COUNTED_FAILURE_CATEGORIES = Set.of(
            "authentication", "model_not_found", "timeout", "rate_limit",
            "upstream_5xx", "network", "protocol", "stream_interrupted"
    );
    private static final Pattern SENSITIVE_MARKER = Pattern.compile(
            "(?i)(authorization|bearer|api[^a-z0-9]*key|access[^a-z0-9]*token|token|secret|password|sk-)"
    );
    private static final int MAX_SUMMARY_LENGTH = 256;

    private final ChannelHealthMapper mapper;
    private final HealthProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public ChannelHealthService(
            ChannelHealthMapper mapper,
            HealthProperties properties,
            ApplicationEventPublisher eventPublisher
    ) {
        this.mapper = mapper;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /** 返回本轮有界探测列表，禁止调度器无界拉取全部渠道。 */
    @Transactional(readOnly = true)
    public List<ChannelHealthTarget> findDueProbeTargets() {
        return mapper.findDueProbeTargets(
                Math.max(1L, properties.probeInterval().toSeconds()),
                properties.batchSize()
        );
    }

    /** 真实 Gateway 成功会清理自动波动状态；人工 disabled 渠道不受影响。 */
    @Transactional
    public void recordGatewaySuccess(UUID channelId) {
        if (channelId == null || mapper.restoreFromGatewaySuccess(channelId) == 0) {
            return;
        }
        ChannelHealthStateRow state = mapper.findState(channelId);
        if (state != null) {
            mapper.aggregateSupplierHealth(state.getSupplierId());
            publishChanged(channelId);
        }
    }

    /** 用户 400/422 等非供应商责任错误不会传入白名单，因此不会增加渠道失败次数。 */
    @Transactional
    public void recordGatewayFailure(UUID channelId, String category, String safeSummary) {
        recordFailure(channelId, category, safeSummary, null, false);
    }

    /** 将一次主动探测结果落入状态机；探测明细只保存固定分类和脱敏摘要。 */
    @Transactional
    public void recordProbeResult(ChannelHealthTarget target, ChannelHealthProbeResult result) {
        if (target == null || target.getChannelId() == null || result == null) {
            return;
        }
        switch (result.outcome()) {
            case HEALTHY -> recordProbeSuccess(target.getChannelId(), result.latencyMs());
            case FAILURE -> recordFailure(
                    target.getChannelId(), result.category(), result.safeSummary(), result.latencyMs(), true
            );
            case UNCONFIGURED -> recordProbeUnconfigured(
                    target.getChannelId(), result.latencyMs(), result.safeSummary()
            );
        }
    }

    /** 管理员手动成功探测会恢复自动波动状态；人工 disabled 仍然拥有最高优先级。 */
    @Transactional
    public void recordManualProbeResult(ChannelHealthTarget target, ChannelHealthProbeResult result) {
        if (target == null || target.getChannelId() == null || result == null) {
            return;
        }
        if ("disabled".equals(target.getStatus())) {
            return;
        }
        if (result.outcome() == ChannelHealthProbeResult.Outcome.HEALTHY) {
            mapper.restoreFromManualProbeSuccess(target.getChannelId());
            ChannelHealthStateRow state = mapper.findState(target.getChannelId());
            if (!isAutoManaged(state)) {
                return;
            }
            mapper.insertHealthCheck(target.getChannelId(), "healthy", safeLatency(result.latencyMs()), null);
            mapper.aggregateSupplierHealth(state.getSupplierId());
            publishChanged(target.getChannelId());
            return;
        }
        recordProbeResult(target, result);
    }

    private void recordProbeSuccess(UUID channelId, int latencyMs) {
        mapper.restoreFromProbeSuccess(channelId);
        ChannelHealthStateRow state = mapper.findState(channelId);
        if (!isAutoManaged(state)) {
            return;
        }
        mapper.insertHealthCheck(channelId, "healthy", safeLatency(latencyMs), null);
        mapper.aggregateSupplierHealth(state.getSupplierId());
        publishChanged(channelId);
    }

    private void recordProbeUnconfigured(UUID channelId, int latencyMs, String safeSummary) {
        ChannelHealthStateRow state = mapper.findState(channelId);
        if (!isAutoManaged(state)) {
            return;
        }
        mapper.insertHealthCheck(
                channelId, "unconfigured", safeLatency(latencyMs), sanitizeSummary(safeSummary)
        );
        // 不支持默认 /models 探测不等于业务调用成功或失败，因此只刷新检测时间，不改供应商健康结论。
        mapper.touchSupplierHealthCheck(state.getSupplierId());
    }

    private void recordFailure(
            UUID channelId,
            String category,
            String safeSummary,
            Integer latencyMs,
            boolean writeHealthCheck
    ) {
        String normalizedCategory = normalizeCategory(category);
        if (channelId == null || normalizedCategory == null) {
            return;
        }
        String storedSummary = sanitizeSummary(safeSummary);
        int updated = mapper.incrementFailure(channelId, storedSummary);
        if (updated == 0) {
            return;
        }
        ChannelHealthStateRow state = mapper.findState(channelId);
        if (!isAutoManaged(state)) {
            return;
        }
        if (writeHealthCheck) {
            mapper.insertHealthCheck(channelId, "degraded", safeLatency(latencyMs), storedSummary);
        }
        mapper.aggregateSupplierHealth(state.getSupplierId());
        publishChanged(channelId);
    }

    private String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String normalized = category.strip().toLowerCase(Locale.ROOT);
        return COUNTED_FAILURE_CATEGORIES.contains(normalized) ? normalized : null;
    }

    /**
     * 即使调用方误传异常文本，也不允许常见凭证标记、控制字符或超长内容落库。
     * 命中敏感标记时退化为固定摘要，不尝试保留原文片段。
     */
    private String sanitizeSummary(String value) {
        if (value == null || value.isBlank()) {
            return "upstream_failure";
        }
        String normalized = value.replaceAll("[\\p{Cntrl}]", "_").strip();
        if (SENSITIVE_MARKER.matcher(normalized).find()) {
            return "upstream_failure";
        }
        return normalized.length() <= MAX_SUMMARY_LENGTH
                ? normalized
                : normalized.substring(0, MAX_SUMMARY_LENGTH);
    }

    private Integer safeLatency(Integer latencyMs) {
        return latencyMs == null ? null : Math.max(0, latencyMs);
    }

    private boolean isAutoManaged(ChannelHealthStateRow state) {
        return state != null && !"disabled".equals(state.getStatus());
    }

    /** 事件在当前事务提交后才会被监听，避免分组告警与渠道状态形成反向事务依赖。 */
    private void publishChanged(UUID channelId) {
        eventPublisher.publishEvent(new ChannelHealthChangedEvent(channelId));
    }
}
