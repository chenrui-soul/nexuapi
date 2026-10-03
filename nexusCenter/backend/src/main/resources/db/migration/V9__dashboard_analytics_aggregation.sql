-- Wave 8：补齐经营仪表盘所需的缓存 Token、上游尝试稳定性和查询索引。
-- 历史收入、成本和毛利继续以 request_logs 的完成时快照为唯一事实来源。

ALTER TABLE usage_aggregates
    ADD COLUMN cached_tokens BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt_success_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt_supplier_failure_count BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt_latency_sum_ms BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN attempt_latency_p95_ms BIGINT,
    ADD CONSTRAINT usage_aggregates_cached_tokens_valid CHECK (cached_tokens >= 0),
    ADD CONSTRAINT usage_aggregates_attempt_counts_valid CHECK (
        attempt_count >= 0
        AND attempt_success_count >= 0
        AND attempt_supplier_failure_count >= 0
        AND attempt_success_count <= attempt_count
        AND attempt_supplier_failure_count <= attempt_count
    ),
    ADD CONSTRAINT usage_aggregates_attempt_latency_valid CHECK (
        attempt_latency_sum_ms >= 0
        AND (attempt_latency_p95_ms IS NULL OR attempt_latency_p95_ms >= 0)
    );

CREATE INDEX idx_usage_aggregates_dashboard
    ON usage_aggregates (dimension_type, bucket_size, bucket_start DESC, dimension_id);
CREATE INDEX idx_request_logs_created_at
    ON request_logs (created_at DESC);
CREATE INDEX idx_upstream_attempt_logs_created_at
    ON upstream_attempt_logs (created_at DESC);

COMMENT ON COLUMN public.usage_aggregates.cached_tokens IS '时间桶内命中上游缓存的输入 Token 总数。';
COMMENT ON COLUMN public.usage_aggregates.attempt_count IS '时间桶内真实上游调用尝试总数，包含首选渠道与重试渠道。';
COMMENT ON COLUMN public.usage_aggregates.attempt_success_count IS '时间桶内真实上游调用成功次数。';
COMMENT ON COLUMN public.usage_aggregates.attempt_supplier_failure_count IS '时间桶内归责供应商的真实上游失败尝试次数。';
COMMENT ON COLUMN public.usage_aggregates.attempt_latency_sum_ms IS '时间桶内真实上游尝试耗时总和，单位毫秒。';
COMMENT ON COLUMN public.usage_aggregates.attempt_latency_p95_ms IS '时间桶内真实上游尝试延迟 P95，单位毫秒。';

