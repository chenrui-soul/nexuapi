package com.nexusapi.server.modules.dashboard.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;

/** Wave 8 经营数据聚合 Mapper，只从脱敏事实表生成可重复覆盖的时间桶快照。 */
@Mapper
public interface DashboardAggregationMapper {
    @Select("SELECT pg_try_advisory_xact_lock(72104208000801)")
    boolean tryAggregationLock();

    @Insert("""
            INSERT INTO usage_aggregates (
                bucket_start, bucket_size, dimension_type, dimension_id,
                request_count, success_count, failure_count, supplier_failure_count,
                input_tokens, output_tokens, cached_tokens, billed_amount,
                supplier_cost_amount, gross_margin_amount, latency_sum_ms, latency_p95_ms
            )
            SELECT date_trunc(#{bucketSize}, timezone('Asia/Shanghai', r.created_at)) AT TIME ZONE 'Asia/Shanghai',
                   #{bucketSize}, d.dimension_type, d.dimension_id,
                   count(*),
                   count(*) FILTER (WHERE r.status_code BETWEEN 200 AND 299 AND r.platform_error_code IS NULL),
                   count(*) - count(*) FILTER (
                       WHERE r.status_code BETWEEN 200 AND 299 AND r.platform_error_code IS NULL
                   ),
                   count(*) FILTER (WHERE r.supplier_error_category IS NOT NULL),
                   coalesce(sum(r.input_tokens), 0), coalesce(sum(r.output_tokens), 0),
                   coalesce(sum(r.cached_tokens), 0), coalesce(sum(r.billed_amount), 0),
                   coalesce(sum(r.supplier_cost_amount), 0), coalesce(sum(r.gross_margin_amount), 0),
                   coalesce(sum(r.duration_ms), 0),
                   CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY r.duration_ms) AS BIGINT)
              FROM request_logs r
              CROSS JOIN LATERAL (VALUES
                    ('platform', 'all'),
                    ('supplier', r.supplier_id::text),
                    ('model', r.model_id::text),
                    ('channel', r.channel_id::text),
                    ('group', r.group_id::text)
              ) AS d(dimension_type, dimension_id)
             WHERE r.created_at >= #{from} AND r.created_at < #{to}
               AND d.dimension_id IS NOT NULL
             GROUP BY 1, d.dimension_type, d.dimension_id
            ON CONFLICT (bucket_start, bucket_size, dimension_type, dimension_id)
            DO UPDATE SET request_count = EXCLUDED.request_count,
                          success_count = EXCLUDED.success_count,
                          failure_count = EXCLUDED.failure_count,
                          supplier_failure_count = EXCLUDED.supplier_failure_count,
                          input_tokens = EXCLUDED.input_tokens,
                          output_tokens = EXCLUDED.output_tokens,
                          cached_tokens = EXCLUDED.cached_tokens,
                          billed_amount = EXCLUDED.billed_amount,
                          supplier_cost_amount = EXCLUDED.supplier_cost_amount,
                          gross_margin_amount = EXCLUDED.gross_margin_amount,
                          latency_sum_ms = EXCLUDED.latency_sum_ms,
                          latency_p95_ms = EXCLUDED.latency_p95_ms,
                          updated_at = now()
            """)
    int upsertRequestAggregates(
            @Param("bucketSize") String bucketSize,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Insert("""
            INSERT INTO usage_aggregates (
                bucket_start, bucket_size, dimension_type, dimension_id,
                attempt_count, attempt_success_count, attempt_supplier_failure_count,
                attempt_latency_sum_ms, attempt_latency_p95_ms
            )
            SELECT date_trunc(#{bucketSize}, timezone('Asia/Shanghai', a.created_at)) AT TIME ZONE 'Asia/Shanghai',
                   #{bucketSize}, d.dimension_type, d.dimension_id,
                   count(*),
                   count(*) FILTER (WHERE a.outcome = 'success'),
                   count(*) FILTER (WHERE a.outcome = 'supplier_failure'),
                   coalesce(sum(a.duration_ms), 0),
                   CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY a.duration_ms) AS BIGINT)
              FROM upstream_attempt_logs a
              CROSS JOIN LATERAL (VALUES
                    ('platform', 'all'),
                    ('supplier', a.supplier_id::text),
                    ('model', a.model_id::text),
                    ('channel', a.channel_id::text)
              ) AS d(dimension_type, dimension_id)
             WHERE a.created_at >= #{from} AND a.created_at < #{to}
             GROUP BY 1, d.dimension_type, d.dimension_id
            ON CONFLICT (bucket_start, bucket_size, dimension_type, dimension_id)
            DO UPDATE SET attempt_count = EXCLUDED.attempt_count,
                          attempt_success_count = EXCLUDED.attempt_success_count,
                          attempt_supplier_failure_count = EXCLUDED.attempt_supplier_failure_count,
                          attempt_latency_sum_ms = EXCLUDED.attempt_latency_sum_ms,
                          attempt_latency_p95_ms = EXCLUDED.attempt_latency_p95_ms,
                          updated_at = now()
            """)
    int upsertAttemptAggregates(
            @Param("bucketSize") String bucketSize,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
