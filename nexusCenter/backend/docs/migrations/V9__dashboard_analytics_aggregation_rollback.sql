DROP INDEX IF EXISTS idx_upstream_attempt_logs_created_at;
DROP INDEX IF EXISTS idx_request_logs_created_at;
DROP INDEX IF EXISTS idx_usage_aggregates_dashboard;

ALTER TABLE usage_aggregates
    DROP CONSTRAINT IF EXISTS usage_aggregates_attempt_latency_valid,
    DROP CONSTRAINT IF EXISTS usage_aggregates_attempt_counts_valid,
    DROP CONSTRAINT IF EXISTS usage_aggregates_cached_tokens_valid,
    DROP COLUMN IF EXISTS attempt_latency_p95_ms,
    DROP COLUMN IF EXISTS attempt_latency_sum_ms,
    DROP COLUMN IF EXISTS attempt_supplier_failure_count,
    DROP COLUMN IF EXISTS attempt_success_count,
    DROP COLUMN IF EXISTS attempt_count,
    DROP COLUMN IF EXISTS cached_tokens;

