package com.nexusapi.server.modules.dashboard.mapper;

import com.nexusapi.server.modules.dashboard.entity.DashboardErrorRow;
import com.nexusapi.server.modules.dashboard.entity.DashboardRankingRow;
import com.nexusapi.server.modules.dashboard.entity.DashboardResourceSummaryRow;
import com.nexusapi.server.modules.dashboard.entity.DashboardSummaryRow;
import com.nexusapi.server.modules.dashboard.entity.DashboardTrendRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

/** 管理端经营仪表盘只读 Mapper，查询字段全部来自脱敏事实或聚合快照。 */
@Mapper
public interface AdminDashboardMapper {
    /**
     * 直接聚合运营总览资源数量，避免管理员列表分页导致总数和启用数失真。
     */
    @Select("""
            SELECT
                (SELECT count(*) FROM suppliers) AS supplier_total,
                (SELECT count(*) FROM suppliers WHERE status = 'active') AS active_supplier_count,
                (SELECT count(*) FROM ai_models) AS model_total,
                (SELECT count(*) FROM ai_models WHERE status = 'active') AS active_model_count,
                (SELECT count(*) FROM channels) AS channel_total,
                (SELECT count(*) FROM channels WHERE status = 'active') AS active_channel_count,
                (SELECT count(*) FROM health_alerts WHERE status = 'open') AS open_alert_count
            """)
    DashboardResourceSummaryRow findResourceSummary();

    @Select("""
            WITH request_summary AS (
                SELECT count(*) AS request_count,
                       count(*) FILTER (WHERE status_code BETWEEN 200 AND 299 AND platform_error_code IS NULL) AS success_count,
                       count(*) - count(*) FILTER (
                           WHERE status_code BETWEEN 200 AND 299 AND platform_error_code IS NULL
                       ) AS failure_count,
                       count(*) FILTER (WHERE supplier_error_category IS NOT NULL) AS supplier_failure_count,
                       coalesce(sum(input_tokens), 0) AS input_tokens,
                       coalesce(sum(output_tokens), 0) AS output_tokens,
                       coalesce(sum(cached_tokens), 0) AS cached_tokens,
                       coalesce(sum(billed_amount), 0) AS billed_amount,
                       coalesce(sum(supplier_cost_amount), 0) AS supplier_cost_amount,
                       coalesce(sum(gross_margin_amount), 0) AS gross_margin_amount,
                       coalesce(sum(duration_ms), 0) AS latency_sum_ms,
                       CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS BIGINT) AS latency_p95_ms
                  FROM request_logs
                 WHERE created_at >= #{from} AND created_at < #{to}
                   AND (CAST(#{supplierId} AS text) IS NULL OR supplier_id::text = #{supplierId})
            ), attempt_summary AS (
                SELECT count(*) AS attempt_count,
                       count(*) FILTER (WHERE outcome = 'success') AS attempt_success_count,
                       count(*) FILTER (WHERE outcome = 'supplier_failure') AS attempt_supplier_failure_count,
                       coalesce(sum(duration_ms), 0) AS attempt_latency_sum_ms,
                       CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms) AS BIGINT) AS attempt_latency_p95_ms
                  FROM upstream_attempt_logs
                 WHERE created_at >= #{from} AND created_at < #{to}
                   AND (CAST(#{supplierId} AS text) IS NULL OR supplier_id::text = #{supplierId})
            )
            SELECT r.*, a.* FROM request_summary r CROSS JOIN attempt_summary a
            """)
    DashboardSummaryRow findSummary(@Param("from") Instant from, @Param("to") Instant to,
                                    @Param("supplierId") String supplierId);

    /**
     * 返回统计区间内真正产生资金数据的供应商结算币种。
     * P0 不执行汇率换算，因此 Service 会拒绝把多个币种静默相加。
     */
    @Select("""
            SELECT DISTINCT supplier_cost_currency
              FROM request_logs
             WHERE created_at >= #{from} AND created_at < #{to}
               AND (CAST(#{supplierId} AS text) IS NULL OR supplier_id::text = #{supplierId})
               AND supplier_cost_currency IS NOT NULL
               AND (
                   supplier_cost_amount <> 0
                   OR billed_amount <> 0
                   OR gross_margin_amount <> 0
               )
             ORDER BY supplier_cost_currency
            """)
    List<String> findSettlementCurrencies(@Param("from") Instant from, @Param("to") Instant to,
                                          @Param("supplierId") String supplierId);

    @Select("""
            SELECT bucket_start, request_count, success_count, failure_count,
                   billed_amount, supplier_cost_amount, gross_margin_amount, latency_p95_ms
              FROM usage_aggregates
             WHERE bucket_size = #{bucketSize}
               AND dimension_type = #{dimensionType} AND dimension_id = #{dimensionId}
               AND bucket_start >= #{from} AND bucket_start < #{to}
             ORDER BY bucket_start
            """)
    List<DashboardTrendRow> findTrend(
            @Param("bucketSize") String bucketSize,
            @Param("dimensionType") String dimensionType,
            @Param("dimensionId") String dimensionId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    @Select("""
            WITH metrics AS (
                SELECT dimension_id,
                       sum(request_count) AS request_count,
                       sum(success_count) AS success_count,
                       sum(failure_count) AS failure_count,
                       sum(input_tokens) AS input_tokens,
                       sum(output_tokens) AS output_tokens,
                       sum(cached_tokens) AS cached_tokens,
                       sum(billed_amount) AS billed_amount,
                       sum(supplier_cost_amount) AS supplier_cost_amount,
                       sum(gross_margin_amount) AS gross_margin_amount,
                       sum(latency_sum_ms) AS latency_sum_ms,
                       sum(attempt_count) AS attempt_count,
                       sum(attempt_success_count) AS attempt_success_count,
                       sum(attempt_supplier_failure_count) AS attempt_supplier_failure_count
                  FROM usage_aggregates
                 WHERE bucket_size = #{bucketSize} AND dimension_type = #{dimensionType}
                   AND bucket_start >= #{from} AND bucket_start < #{to}
                 GROUP BY dimension_id
            )
            SELECT m.*,
                   coalesce(
                       CASE #{dimensionType}
                           WHEN 'supplier' THEN (SELECT s.name FROM suppliers s WHERE s.id::text = m.dimension_id)
                           WHEN 'model' THEN (SELECT ai.display_name FROM ai_models ai WHERE ai.id::text = m.dimension_id)
                           WHEN 'channel' THEN (SELECT c.name FROM channels c WHERE c.id::text = m.dimension_id)
                           WHEN 'group' THEN (SELECT g.name FROM routing_groups g WHERE g.id::text = m.dimension_id)
                       END,
                       m.dimension_id
                   ) AS dimension_name
              FROM metrics m
             ORDER BY m.gross_margin_amount DESC, m.request_count DESC, m.dimension_id
             LIMIT #{limit}
            """)
    List<DashboardRankingRow> findRanking(
            @Param("dimensionType") String dimensionType,
            @Param("bucketSize") String bucketSize,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit
    );

    /** 供应商筛选时从最终请求事实表聚合，确保模型、渠道和分组只统计该供应商。 */
    @Select("""
            WITH request_metrics AS (
                SELECT CASE #{dimensionType}
                           WHEN 'supplier' THEN r.supplier_id::text
                           WHEN 'model' THEN r.model_id::text
                           WHEN 'channel' THEN r.channel_id::text
                           WHEN 'group' THEN r.group_id::text
                       END AS dimension_id,
                       count(*) AS request_count,
                       count(*) FILTER (WHERE r.status_code BETWEEN 200 AND 299 AND r.platform_error_code IS NULL) AS success_count,
                       count(*) - count(*) FILTER (WHERE r.status_code BETWEEN 200 AND 299 AND r.platform_error_code IS NULL) AS failure_count,
                       coalesce(sum(r.input_tokens), 0) AS input_tokens,
                       coalesce(sum(r.output_tokens), 0) AS output_tokens,
                       coalesce(sum(r.cached_tokens), 0) AS cached_tokens,
                       coalesce(sum(r.billed_amount), 0) AS billed_amount,
                       coalesce(sum(r.supplier_cost_amount), 0) AS supplier_cost_amount,
                       coalesce(sum(r.gross_margin_amount), 0) AS gross_margin_amount,
                       coalesce(sum(r.duration_ms), 0) AS latency_sum_ms
                  FROM request_logs r
                 WHERE r.created_at >= #{from} AND r.created_at < #{to}
                   AND r.supplier_id::text = #{supplierId}
                 GROUP BY 1
            ), attempt_metrics AS (
                SELECT CASE #{dimensionType}
                           WHEN 'supplier' THEN a.supplier_id::text
                           WHEN 'model' THEN a.model_id::text
                           WHEN 'channel' THEN a.channel_id::text
                       END AS dimension_id,
                       count(*) AS attempt_count,
                       count(*) FILTER (WHERE a.outcome = 'success') AS attempt_success_count,
                       count(*) FILTER (WHERE a.outcome = 'supplier_failure') AS attempt_supplier_failure_count
                  FROM upstream_attempt_logs a
                 WHERE a.created_at >= #{from} AND a.created_at < #{to}
                   AND a.supplier_id::text = #{supplierId}
                 GROUP BY 1
            )
            SELECT r.dimension_id,
                   coalesce(CASE #{dimensionType}
                       WHEN 'supplier' THEN (SELECT s.name FROM suppliers s WHERE s.id::text = r.dimension_id)
                       WHEN 'model' THEN (SELECT m.display_name FROM ai_models m WHERE m.id::text = r.dimension_id)
                       WHEN 'channel' THEN (SELECT c.name FROM channels c WHERE c.id::text = r.dimension_id)
                       WHEN 'group' THEN (SELECT g.name FROM routing_groups g WHERE g.id::text = r.dimension_id)
                   END, r.dimension_id) AS dimension_name,
                   r.request_count, r.success_count, r.failure_count,
                   r.input_tokens, r.output_tokens, r.cached_tokens, r.billed_amount,
                   r.supplier_cost_amount, r.gross_margin_amount, r.latency_sum_ms,
                   coalesce(a.attempt_count, 0) AS attempt_count,
                   coalesce(a.attempt_success_count, 0) AS attempt_success_count,
                   coalesce(a.attempt_supplier_failure_count, 0) AS attempt_supplier_failure_count
              FROM request_metrics r
              LEFT JOIN attempt_metrics a ON a.dimension_id = r.dimension_id
             WHERE r.dimension_id IS NOT NULL
             ORDER BY r.gross_margin_amount DESC, r.request_count DESC, r.dimension_id
             LIMIT #{limit}
            """)
    List<DashboardRankingRow> findRankingBySupplier(
            @Param("dimensionType") String dimensionType,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("supplierId") String supplierId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT coalesce(nullif(error_category, ''), outcome) AS category,
                   count(*) AS occurrence_count
              FROM upstream_attempt_logs
             WHERE created_at >= #{from} AND created_at < #{to}
               AND (CAST(#{supplierId} AS text) IS NULL OR supplier_id::text = #{supplierId})
               AND outcome NOT IN ('success', 'client_cancelled')
             GROUP BY 1
             ORDER BY occurrence_count DESC, category
             LIMIT #{limit}
            """)
    List<DashboardErrorRow> findErrorDistribution(
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("supplierId") String supplierId,
            @Param("limit") int limit
    );

    @Select("""
            SELECT max(updated_at)
              FROM usage_aggregates
              WHERE bucket_size = #{bucketSize}
               AND dimension_type = #{dimensionType} AND dimension_id = #{dimensionId}
               AND bucket_start >= #{from} AND bucket_start < #{to}
            """)
    Instant findLastAggregatedAt(
            @Param("bucketSize") String bucketSize,
            @Param("dimensionType") String dimensionType,
            @Param("dimensionId") String dimensionId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );
}
