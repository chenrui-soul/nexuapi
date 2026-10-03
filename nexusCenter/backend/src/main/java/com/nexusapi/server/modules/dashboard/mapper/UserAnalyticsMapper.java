package com.nexusapi.server.modules.dashboard.mapper;

import com.nexusapi.server.modules.dashboard.entity.UserAnalyticsRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 用户分析只读 Mapper；所有用户明细查询必须显式限定当前 user_id。 */
@Mapper
public interface UserAnalyticsMapper {
    /** 统计当前用户在指定区间内的请求、Token、积分和平台总耗时。 */
    @Select("""
            SELECT count(*) AS request_count,
                   count(*) FILTER (WHERE status_code BETWEEN 200 AND 299) AS success_count,
                   count(*) FILTER (WHERE status_code IS NULL OR status_code NOT BETWEEN 200 AND 299)
                       AS failure_count,
                   coalesce(sum(input_tokens), 0) AS input_tokens,
                   coalesce(sum(output_tokens), 0) AS output_tokens,
                   coalesce(sum(cached_tokens), 0) AS cached_tokens,
                   coalesce(sum(billed_amount), 0) AS billed_amount,
                   count(duration_ms) AS latency_count,
                   coalesce(sum(duration_ms), 0) AS latency_sum_ms,
                   CAST(percentile_cont(0.50) WITHIN GROUP (ORDER BY duration_ms)
                       FILTER (WHERE duration_ms IS NOT NULL) AS BIGINT) AS latency_p50_ms,
                   CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY duration_ms)
                       FILTER (WHERE duration_ms IS NOT NULL) AS BIGINT) AS latency_p95_ms
              FROM request_logs
             WHERE user_id = #{userId}
               AND created_at >= #{from} AND created_at < #{to}
            """)
    UserAnalyticsRow.Summary findSummary(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /** 按小时或自然日聚合当前用户趋势，时间边界统一使用 Asia/Shanghai。 */
    @Select("""
            SELECT CASE WHEN #{bucketSize} = 'hour'
                        THEN date_trunc('hour', created_at AT TIME ZONE 'Asia/Shanghai')
                             AT TIME ZONE 'Asia/Shanghai'
                        ELSE date_trunc('day', created_at AT TIME ZONE 'Asia/Shanghai')
                             AT TIME ZONE 'Asia/Shanghai'
                   END AS bucket_start,
                   count(*) AS request_count,
                   count(*) FILTER (WHERE status_code BETWEEN 200 AND 299) AS success_count,
                   count(*) FILTER (WHERE status_code IS NULL OR status_code NOT BETWEEN 200 AND 299)
                       AS failure_count,
                   coalesce(sum(billed_amount), 0) AS billed_amount,
                   coalesce(sum(input_tokens), 0) AS input_tokens,
                   coalesce(sum(output_tokens), 0) AS output_tokens,
                   coalesce(sum(cached_tokens), 0) AS cached_tokens
              FROM request_logs
             WHERE user_id = #{userId}
               AND created_at >= #{from} AND created_at < #{to}
             GROUP BY 1
             ORDER BY 1
            """)
    List<UserAnalyticsRow.Trend> findTrend(
            @Param("userId") UUID userId,
            @Param("bucketSize") String bucketSize,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /** 按模型能力统计当前用户的真实积分分布。 */
    @Select("""
            SELECT coalesce(nullif(m.capability_type, ''), 'other') AS capability_type,
                   count(*) AS request_count,
                   coalesce(sum(r.billed_amount), 0) AS billed_amount
              FROM request_logs r
              LEFT JOIN ai_models m ON m.id = r.model_id
             WHERE r.user_id = #{userId}
               AND r.created_at >= #{from} AND r.created_at < #{to}
             GROUP BY 1
             ORDER BY billed_amount DESC, capability_type
            """)
    List<UserAnalyticsRow.Capability> findCapabilityDistribution(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /** 当前用户按模型的积分排行。 */
    @Select("""
            SELECT coalesce(m.id::text, r.public_model) AS dimension_id,
                   coalesce(m.display_name, r.public_model) AS dimension_name,
                   count(*) AS request_count,
                   coalesce(sum(r.billed_amount), 0) AS billed_amount
              FROM request_logs r
              LEFT JOIN ai_models m ON m.id = r.model_id
             WHERE r.user_id = #{userId}
               AND r.created_at >= #{from} AND r.created_at < #{to}
             GROUP BY 1, 2
             ORDER BY billed_amount DESC, request_count DESC, dimension_name
             LIMIT #{limit}
            """)
    List<UserAnalyticsRow.Ranking> findModelRanking(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit
    );

    /** 当前用户按自己的 API Key 名称统计积分排行。 */
    @Select("""
            SELECT coalesce(k.id::text, 'deleted') AS dimension_id,
                   coalesce(k.name, '已删除 API Key') AS dimension_name,
                   count(*) AS request_count,
                   coalesce(sum(r.billed_amount), 0) AS billed_amount
              FROM request_logs r
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
             WHERE r.user_id = #{userId}
               AND r.created_at >= #{from} AND r.created_at < #{to}
             GROUP BY 1, 2
             ORDER BY billed_amount DESC, request_count DESC, dimension_name
             LIMIT #{limit}
            """)
    List<UserAnalyticsRow.Ranking> findApiKeyRanking(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit
    );

    /** 当前用户按服务分组统计积分排行，只返回分组名称和倍率之外的安全聚合。 */
    @Select("""
            SELECT coalesce(g.id::text, 'unknown') AS dimension_id,
                   coalesce(g.name, '未知服务分组') AS dimension_name,
                   count(*) AS request_count,
                   coalesce(sum(r.billed_amount), 0) AS billed_amount
              FROM request_logs r
              LEFT JOIN routing_groups g ON g.id = r.group_id
             WHERE r.user_id = #{userId}
               AND r.created_at >= #{from} AND r.created_at < #{to}
             GROUP BY 1, 2
             ORDER BY billed_amount DESC, request_count DESC, dimension_name
             LIMIT #{limit}
            """)
    List<UserAnalyticsRow.Ranking> findGroupRanking(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit
    );

    /** 仪表盘最近调用白名单，不读取上游模型、渠道、供应商、成本和路由字段。 */
    @Select("""
            SELECT r.request_id, r.started_at, r.public_model,
                   k.name AS api_key_name, g.name AS service_group_name,
                   r.status_code, r.platform_error_code,
                   r.input_tokens, r.output_tokens, r.cached_tokens,
                   r.billed_amount, r.duration_ms, r.streaming, r.retry_count
              FROM request_logs r
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
             WHERE r.user_id = #{userId}
               AND r.created_at >= #{from} AND r.created_at < #{to}
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT #{limit}
            """)
    List<UserAnalyticsRow.RecentRequest> findRecentRequests(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("limit") int limit
    );

    /** 按星期和小时聚合当前用户活动热力数据。 */
    @Select("""
            SELECT extract(isodow FROM created_at AT TIME ZONE 'Asia/Shanghai')::integer AS day_of_week,
                   extract(hour FROM created_at AT TIME ZONE 'Asia/Shanghai')::integer AS hour_of_day,
                   count(*) AS request_count,
                   coalesce(sum(billed_amount), 0) AS billed_amount
              FROM request_logs
             WHERE user_id = #{userId}
               AND created_at >= #{from} AND created_at < #{to}
             GROUP BY 1, 2
             ORDER BY 1, 2
            """)
    List<UserAnalyticsRow.Activity> findActivityHeatmap(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /** 最近一分钟请求指标用于用户仪表盘的实时流量卡片。 */
    @Select("""
            SELECT count(*) AS request_count,
                   coalesce(sum(input_tokens), 0) AS input_tokens,
                   coalesce(sum(output_tokens), 0) AS output_tokens,
                   coalesce(sum(cached_tokens), 0) AS cached_tokens,
                   coalesce(sum(billed_amount), 0) AS billed_amount
              FROM request_logs
             WHERE user_id = #{userId}
               AND created_at >= now() - interval '1 minute'
            """)
    UserAnalyticsRow.Live findLiveMetrics(@Param("userId") UUID userId);

    /** 当前用户最近一条请求时间，用于显示真实数据更新时间。 */
    @Select("""
            SELECT max(created_at)
              FROM request_logs
             WHERE user_id = #{userId}
               AND created_at >= #{from} AND created_at < #{to}
            """)
    Instant findLatestRequestAt(
            @Param("userId") UUID userId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /**
     * 查询当前用户有权看到的服务分组，并基于每个分组最近最多 60 次真实业务请求聚合状态。
     * 请求样本跨用户匿名汇总，不返回任何用户标识或上游内部字段。
     */
    @Select("""
            WITH visible_groups AS (
                SELECT g.id, g.name, g.description, g.price_multiplier
                  FROM routing_groups g
                 WHERE g.status = 'active'
                   AND (
                        g.audience = 'all'
                        OR (g.audience = 'assigned' AND EXISTS (
                            SELECT 1
                              FROM routing_group_user_grants grant_row
                              JOIN users authorized_user ON authorized_user.id = grant_row.user_id
                             WHERE grant_row.group_id = g.id
                               AND grant_row.user_id = #{userId}
                               AND grant_row.status = 'active'
                               AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                               AND authorized_user.status = 'active'
                               AND authorized_user.deleted_at IS NULL
                        ))
                   )
            ), model_counts AS (
                SELECT vg.id AS group_id,
                       count(rgm.model_id) FILTER (WHERE rgm.source_status = 'active') AS total_model_count,
                       count(m.id) FILTER (
                           WHERE rgm.source_status = 'active'
                             AND m.status = 'active' AND m.public_visible = true
                       ) AS available_model_count
                  FROM visible_groups vg
                  LEFT JOIN routing_group_models rgm ON rgm.group_id = vg.id
                  LEFT JOIN ai_models m ON m.id = rgm.model_id
                 GROUP BY vg.id
            ), sample_stats AS (
                SELECT vg.id AS group_id,
                       count(sample.request_id) AS sample_count,
                       count(sample.request_id) FILTER (
                           WHERE sample.status_code BETWEEN 200 AND 299
                       ) AS success_count,
                       count(sample.duration_ms) AS latency_count,
                       coalesce(sum(sample.duration_ms), 0) AS latency_sum_ms,
                       CAST(percentile_cont(0.95) WITHIN GROUP (ORDER BY sample.duration_ms)
                           FILTER (WHERE sample.duration_ms IS NOT NULL) AS BIGINT) AS latency_p95_ms,
                       max(sample.created_at) AS last_request_at
                  FROM visible_groups vg
                  LEFT JOIN LATERAL (
                      SELECT r.request_id, r.status_code, r.duration_ms, r.created_at
                        FROM request_logs r
                       WHERE r.group_id = vg.id
                       ORDER BY r.created_at DESC, r.id DESC
                       LIMIT 60
                  ) sample ON true
                 GROUP BY vg.id
            )
            SELECT vg.id::text AS group_id, vg.name, vg.description, vg.price_multiplier,
                   coalesce(mc.available_model_count, 0) AS available_model_count,
                   coalesce(mc.total_model_count, 0) AS total_model_count,
                   coalesce(ss.sample_count, 0) AS sample_count,
                   coalesce(ss.success_count, 0) AS success_count,
                   coalesce(ss.latency_count, 0) AS latency_count,
                   coalesce(ss.latency_sum_ms, 0) AS latency_sum_ms,
                   ss.latency_p95_ms, ss.last_request_at
              FROM visible_groups vg
              LEFT JOIN model_counts mc ON mc.group_id = vg.id
              LEFT JOIN sample_stats ss ON ss.group_id = vg.id
             ORDER BY vg.price_multiplier, vg.name, vg.id
            """)
    List<UserAnalyticsRow.GroupStatus> findVisibleGroupStatuses(@Param("userId") UUID userId);

    /** 返回用户可见分组最近最多 60 次真实请求的安全状态序列，按过去到现在排序。 */
    @Select("""
            WITH visible_groups AS (
                SELECT g.id
                  FROM routing_groups g
                 WHERE g.status = 'active'
                   AND (
                        g.audience = 'all'
                        OR (g.audience = 'assigned' AND EXISTS (
                            SELECT 1
                              FROM routing_group_user_grants grant_row
                              JOIN users authorized_user ON authorized_user.id = grant_row.user_id
                             WHERE grant_row.group_id = g.id
                               AND grant_row.user_id = #{userId}
                               AND grant_row.status = 'active'
                               AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                               AND authorized_user.status = 'active'
                               AND authorized_user.deleted_at IS NULL
                        ))
                   )
            )
            SELECT vg.id::text AS group_id, sample.created_at AS occurred_at,
                   sample.status_code, sample.duration_ms
              FROM visible_groups vg
              CROSS JOIN LATERAL (
                  SELECT r.status_code, r.duration_ms, r.created_at, r.id
                    FROM request_logs r
                   WHERE r.group_id = vg.id
                   ORDER BY r.created_at DESC, r.id DESC
                   LIMIT 60
              ) sample
             ORDER BY vg.id, sample.created_at, sample.id
            """)
    List<UserAnalyticsRow.GroupHistory> findVisibleGroupHistory(@Param("userId") UUID userId);
}
