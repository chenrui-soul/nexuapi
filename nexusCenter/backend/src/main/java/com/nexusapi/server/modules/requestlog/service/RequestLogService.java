package com.nexusapi.server.modules.requestlog.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexusapi.server.modules.gateway.support.GatewayPricingService;
import com.nexusapi.server.modules.gateway.support.OpenAiUsage;
import com.nexusapi.server.modules.requestlog.mapper.RequestLogMapper;
import com.nexusapi.server.modules.requestlog.model.GatewayBillingDetail;
import com.nexusapi.server.modules.requestlog.model.GatewayRequestLog;
import com.nexusapi.server.modules.requestlog.model.GatewayUpstreamAttemptLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/** 写入脱敏调用日志；日志库异常不能反向造成已经完成的上游调用重复执行。 */
@Service
public class RequestLogService {
    private static final Logger log = LoggerFactory.getLogger(RequestLogService.class);
    private static final int QUEUE_CAPACITY = 20_000;
    private static final int BATCH_SIZE = 100;
    private final RequestLogMapper mapper;
    private final ObjectMapper objectMapper;
    private final RequestPayloadSummaryService payloadSummaryService;
    /** 测试环境可关闭异步队列，保证断言读取日志时不会与后台批处理竞态。生产默认保持异步批量写入。 */
    private final boolean asyncEnabled;
    private final BlockingQueue<GatewayRequestLog> requestQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final BlockingQueue<GatewayUpstreamAttemptLog> attemptQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private volatile boolean running = true;
    private Thread worker;

    public RequestLogService(
            RequestLogMapper mapper,
            ObjectMapper objectMapper,
            RequestPayloadSummaryService payloadSummaryService,
            @Value("${nexus.request-log.async-enabled:true}") boolean asyncEnabled
    ) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.payloadSummaryService = payloadSummaryService;
        this.asyncEnabled = asyncEnabled;
    }

    @PostConstruct
    void startWriter() {
        worker = Thread.ofPlatform().name("gateway-telemetry-writer").daemon(false).start(this::writeLoop);
    }

    @PreDestroy
    void stopWriter() {
        running = false;
        if (worker == null) return;
        worker.interrupt();
        try {
            worker.join(TimeUnit.SECONDS.toMillis(30));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /** 请求完成后进入有界队列；队列满时回退同步写入，保证不静默丢记录。 */
    public void recordAsync(GatewayRequestLog row) {
        if (!asyncEnabled) {
            record(row);
            return;
        }
        if (!requestQueue.offer(row)) record(row);
    }

    public void record(GatewayRequestLog row) {
        row = enrichPayload(row);
        try {
            mapper.insert(row);
        } catch (DataAccessException exception) {
            // 只记录平台 request_id，禁止把携带路由密文的对象或异常 SQL 参数输出到日志。
            log.error("Failed to persist gateway request log, requestId={}", row.requestId());
        }
    }

    private GatewayRequestLog enrichPayload(GatewayRequestLog row) {
        if (row == null) return null;
        RequestPayloadSummaryService.Snapshot snapshot = payloadSummaryService.take(row.requestId());
        return new GatewayRequestLog(
                row.id(), row.requestId(), row.userId(), row.apiKeyId(), row.modelId(), row.supplierId(),
                row.channelId(), row.channelModelId(), row.groupId(), row.publicModel(), row.upstreamModel(),
                row.startedAt(), row.completedAt(), row.durationMs(), row.statusCode(), row.platformErrorCode(),
                row.inputTokens(), row.outputTokens(), row.cachedTokens(), row.billedAmount(), row.priceMultiplier(),
                row.supplierInputPrice(), row.supplierCachedInputPrice(), row.supplierOutputPrice(), row.supplierCostAmount(),
                row.supplierCostCurrency(), row.grossMarginAmount(), row.supplierErrorCategory(), row.streaming(),
                row.retryCount(), row.clientIp(), row.userAgentHash(), row.upstreamErrorSummary(), row.routeSwitchReason(),
                snapshot.requestJson(), snapshot.responseJson(), snapshot.requestDetailJson(), snapshot.responseDetailJson(),
                snapshot.requestSize(), snapshot.responseSize()
        );
    }

    public void recordAttempt(GatewayUpstreamAttemptLog row) {
        try {
            mapper.insertAttempt(row);
        } catch (DataAccessException exception) {
            // 尝试日志属于观测支线，失败时只记录 request_id，不能泄露错误 SQL 参数或改变调用结果。
            log.error("Failed to persist upstream attempt log, requestId={}", row.requestId());
        }
    }

    /** 每个上游尝试异步写入，避免重试观测拖慢主请求。 */
    public void recordAttemptAsync(GatewayUpstreamAttemptLog row) {
        if (!asyncEnabled) {
            recordAttempt(row);
            return;
        }
        if (!attemptQueue.offer(row)) recordAttempt(row);
    }

    private void writeLoop() {
        List<GatewayRequestLog> requestBatch = new ArrayList<>(BATCH_SIZE);
        List<GatewayUpstreamAttemptLog> attemptBatch = new ArrayList<>(BATCH_SIZE);
        while (running || !requestQueue.isEmpty() || !attemptQueue.isEmpty()) {
            try {
                GatewayRequestLog first = requestQueue.poll(50, TimeUnit.MILLISECONDS);
                if (first != null) requestBatch.add(first);
                requestQueue.drainTo(requestBatch, BATCH_SIZE - requestBatch.size());
                attemptQueue.drainTo(attemptBatch, BATCH_SIZE);
                persistRequestBatch(requestBatch);
                persistAttemptBatch(attemptBatch);
            } catch (InterruptedException exception) {
                // 关闭时唤醒循环，继续把队列内剩余数据刷完。
            } finally {
                requestBatch.clear();
                attemptBatch.clear();
            }
        }
    }

    private void persistRequestBatch(List<GatewayRequestLog> rows) {
        if (rows.isEmpty()) return;
        List<GatewayRequestLog> enriched = rows.stream().map(this::enrichPayload).toList();
        try {
            mapper.insertBatch(enriched);
        } catch (DataAccessException exception) {
            log.error("Failed to batch persist gateway request logs, size={}", enriched.size());
            enriched.forEach(this::recordPersisted);
        }
    }

    private void recordPersisted(GatewayRequestLog row) {
        try {
            mapper.insert(row);
        } catch (DataAccessException exception) {
            log.error("Failed to persist gateway request log, requestId={}", row.requestId());
        }
    }

    private void persistAttemptBatch(List<GatewayUpstreamAttemptLog> rows) {
        if (rows.isEmpty()) return;
        try {
            mapper.insertAttemptBatch(rows);
        } catch (DataAccessException exception) {
            log.error("Failed to batch persist upstream attempt logs, size={}", rows.size());
            rows.forEach(this::recordAttempt);
        }
    }

    /** 价格快照写入失败不反向改变已完成的资金结算，但会留下 requestId 级错误日志。 */
    public void recordBillingDetail(
            String requestId,
            UUID userId,
            UUID apiKeyId,
            UUID modelId,
            UUID groupId,
            BigDecimal groupMultiplier,
            OpenAiUsage usage,
            GatewayPricingService.ChargeDecision decision,
            String status
    ) {
        GatewayPricingService.PricingUsage pricingUsage = new GatewayPricingService.PricingUsage(
                usage.inputTokens(), usage.outputTokens(), usage.cachedInputTokens(),
                usage.cacheWrite5mInputTokens(), usage.cacheWrite1hInputTokens(),
                usage.audioInputTokens(), usage.audioOutputTokens(),
                1, 0, 0, 1
        );
        recordBillingDetail(
                requestId, userId, apiKeyId, modelId, groupId, groupMultiplier,
                pricingUsage, decision, status
        );
    }

    /** 媒体能力使用数量、时长或字符数快照，避免把非 Token 用量伪装成 Token。 */
    public void recordBillingDetail(
            String requestId,
            UUID userId,
            UUID apiKeyId,
            UUID modelId,
            UUID groupId,
            BigDecimal groupMultiplier,
            GatewayPricingService.PricingUsage usage,
            GatewayPricingService.ChargeDecision decision,
            String status
    ) {
        try {
            Map<String, Object> usageSnapshot = new LinkedHashMap<>();
            usageSnapshot.put("input_tokens", usage.inputTokens());
            usageSnapshot.put("output_tokens", usage.outputTokens());
            usageSnapshot.put("ordinary_input_tokens", Math.max(0L,
                    usage.inputTokens() - usage.cachedInputTokens() - usage.cacheWrite5mInputTokens()
                            - usage.cacheWrite1hInputTokens() - usage.audioInputTokens()));
            usageSnapshot.put("ordinary_output_tokens", Math.max(0L,
                    usage.outputTokens() - usage.audioOutputTokens()));
            usageSnapshot.put("cached_input_tokens", usage.cachedInputTokens());
            usageSnapshot.put("cache_write_5m_input_tokens", usage.cacheWrite5mInputTokens());
            usageSnapshot.put("cache_write_1h_input_tokens", usage.cacheWrite1hInputTokens());
            usageSnapshot.put("audio_input_tokens", usage.audioInputTokens());
            usageSnapshot.put("audio_output_tokens", usage.audioOutputTokens());
            usageSnapshot.put("quantity", usage.quantity());
            usageSnapshot.put("duration_millis", usage.durationMillis());
            usageSnapshot.put("character_count", usage.characterCount());
            usageSnapshot.put("request_count", usage.requestCount());
            Map<String, Object> calculation = new LinkedHashMap<>();
            calculation.put("formula_version", 2);
            calculation.put("engine_mode", decision.engineMode());
            calculation.put("billing_type", decision.billingType());
            calculation.put("formula", "base_usage_amount * time_multiplier * group_multiplier");
            calculation.put("pricing_timezone", decision.pricingTimezone());
            mapper.insertBillingDetail(new GatewayBillingDetail(
                    UUID.randomUUID(), requestId, userId, apiKeyId, modelId, groupId,
                    decision.pricingVersionId(), decision.matchedRuleId(), decision.contextTierId(),
                    decision.engineMode(), decision.billingType(), decision.baseUnitPrice(),
                    decision.effectiveUnitPrice(), decision.inputTokenRatio(), decision.outputTokenRatio(),
                    decision.cachedInputTokenRatio(), decision.cacheWrite5mTokenRatio(),
                    decision.cacheWrite1hTokenRatio(), decision.audioInputTokenRatio(),
                    decision.audioOutputTokenRatio(), groupMultiplier,
                    decision.timeRuleId(), decision.timeRuleName(), decision.timeMultiplier(),
                    decision.pricingTime(), decision.baseUsageAmount(),
                    toJson(usageSnapshot), toJson(calculation),
                    decision.legacyAmount(), decision.v2Amount() == null ? decision.legacyAmount() : decision.v2Amount(),
                    decision.settlementAmount().setScale(12), status
            ));
        } catch (DataAccessException | JsonProcessingException exception) {
            log.error("Failed to persist request billing detail, requestId={}, type={}",
                    requestId, exception.getClass().getName());
        }
    }

    private String toJson(Object value) throws JsonProcessingException {
        return objectMapper.writeValueAsString(value);
    }
}
