package com.nexusapi.server.modules.requestlog.mapper;

import com.nexusapi.server.modules.requestlog.model.GatewayRequestLog;
import com.nexusapi.server.modules.requestlog.model.GatewayBillingDetail;
import com.nexusapi.server.modules.requestlog.model.GatewayUpstreamAttemptLog;
import com.nexusapi.server.modules.requestlog.entity.UserRequestLogRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;
import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** request_logs 仅追加 Mapper，使用显式字段白名单防止敏感对象被整体序列化。 */
@Mapper
public interface RequestLogMapper {

    /** 查询当前用户可见的调用日志白名单，供应商和内部路由字段不进入 SELECT。 */
    @Select("""
            <script>
            SELECT r.id, r.request_id, r.started_at, r.completed_at, r.duration_ms,
                   r.public_model, k.name AS api_key_name, g.name AS service_group_name,
                   r.status_code, r.platform_error_code, r.input_tokens, r.output_tokens,
                   r.cached_tokens, r.billed_amount, r.streaming, r.retry_count
              FROM request_logs r
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
             WHERE r.user_id = #{userId}
               <if test="query != null and query != ''">
                 AND (r.request_id ILIKE concat('%', #{query}, '%')
                      OR r.public_model ILIKE concat('%', #{query}, '%')
                      OR COALESCE(k.name, '') ILIKE concat('%', #{query}, '%')
                      OR COALESCE(g.name, '') ILIKE concat('%', #{query}, '%'))
               </if>
               <if test="status != null and status == 'success'">AND r.status_code BETWEEN 200 AND 299</if>
               <if test="status != null and status == 'failed'">AND (r.status_code IS NULL OR r.status_code NOT BETWEEN 200 AND 299)</if>
               <if test="model != null and model != ''">AND r.public_model = #{model}</if>
               AND r.created_at &gt;= #{from}
               AND r.created_at &lt; #{to}
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "userRequestLogListRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "completed_at", property = "completedAt"),
            @Result(column = "duration_ms", property = "durationMs"),
            @Result(column = "public_model", property = "publicModel"),
            @Result(column = "api_key_name", property = "apiKeyName"),
            @Result(column = "service_group_name", property = "serviceGroupName"),
            @Result(column = "status_code", property = "statusCode"),
            @Result(column = "platform_error_code", property = "platformErrorCode"),
            @Result(column = "input_tokens", property = "inputTokens"),
            @Result(column = "output_tokens", property = "outputTokens"),
            @Result(column = "cached_tokens", property = "cachedTokens"),
            @Result(column = "billed_amount", property = "billedAmount"),
            @Result(column = "streaming", property = "streaming"),
            @Result(column = "retry_count", property = "retryCount")
    })
    List<UserRequestLogRow> findUserPage(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("status") String status,
            @Param("model") String model,
            @Param("from") Instant from,
            @Param("to") Instant to,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    /** 使用与列表相同的用户边界和筛选条件统计调用日志总数。 */
    @Select("""
            <script>
            SELECT count(*)
              FROM request_logs r
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
             WHERE r.user_id = #{userId}
               <if test="query != null and query != ''">
                 AND (r.request_id ILIKE concat('%', #{query}, '%')
                      OR r.public_model ILIKE concat('%', #{query}, '%')
                      OR COALESCE(k.name, '') ILIKE concat('%', #{query}, '%')
                      OR COALESCE(g.name, '') ILIKE concat('%', #{query}, '%'))
               </if>
               <if test="status != null and status == 'success'">AND r.status_code BETWEEN 200 AND 299</if>
               <if test="status != null and status == 'failed'">AND (r.status_code IS NULL OR r.status_code NOT BETWEEN 200 AND 299)</if>
               <if test="model != null and model != ''">AND r.public_model = #{model}</if>
               AND r.created_at &gt;= #{from}
               AND r.created_at &lt; #{to}
            </script>
            """)
    long countUserPage(
            @Param("userId") UUID userId,
            @Param("query") String query,
            @Param("status") String status,
            @Param("model") String model,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /** 按 requestId 和当前用户共同查询单条日志，避免跨用户越权。 */
    @Select("""
            SELECT r.id, r.request_id, r.started_at, r.completed_at, r.duration_ms,
                   r.public_model, k.name AS api_key_name, g.name AS service_group_name,
                   r.status_code, r.platform_error_code, r.input_tokens, r.output_tokens,
                   r.cached_tokens, r.billed_amount, r.streaming, r.retry_count,
                   r.request_summary::text, r.response_summary::text,
                   r.request_detail::text, r.response_detail::text,
                   r.request_payload_size, r.response_payload_size
              FROM request_logs r
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
             WHERE r.user_id = #{userId}
               AND r.request_id = #{requestId}
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT 1
            """)
    @Results(id = "userRequestLogDetailRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "completed_at", property = "completedAt"),
            @Result(column = "duration_ms", property = "durationMs"),
            @Result(column = "public_model", property = "publicModel"),
            @Result(column = "api_key_name", property = "apiKeyName"),
            @Result(column = "service_group_name", property = "serviceGroupName"),
            @Result(column = "status_code", property = "statusCode"),
            @Result(column = "platform_error_code", property = "platformErrorCode"),
            @Result(column = "input_tokens", property = "inputTokens"),
            @Result(column = "output_tokens", property = "outputTokens"),
            @Result(column = "cached_tokens", property = "cachedTokens"),
            @Result(column = "billed_amount", property = "billedAmount"),
            @Result(column = "streaming", property = "streaming"),
            @Result(column = "retry_count", property = "retryCount"),
            @Result(column = "request_summary", property = "requestSummaryJson"),
            @Result(column = "response_summary", property = "responseSummaryJson"),
            @Result(column = "request_detail", property = "requestDetailJson"),
            @Result(column = "response_detail", property = "responseDetailJson"),
            @Result(column = "request_payload_size", property = "requestPayloadSize"),
            @Result(column = "response_payload_size", property = "responsePayloadSize")
    })
    UserRequestLogRow findUserByRequestId(@Param("userId") UUID userId, @Param("requestId") String requestId);
    @Insert("""
            INSERT INTO request_billing_details (
                id, request_id, user_id, api_key_id, model_id, group_id,
                pricing_version_id, matched_rule_id, context_tier_id,
                engine_mode, billing_type, base_unit_price, effective_unit_price,
                input_token_ratio, output_token_ratio, cached_input_token_ratio,
                cache_write_5m_token_ratio, cache_write_1h_token_ratio,
                audio_input_token_ratio, audio_output_token_ratio,
                group_multiplier, time_rule_id, time_rule_name, time_multiplier, pricing_time,
                base_usage_amount, usage_snapshot, calculation_snapshot,
                legacy_amount, calculated_amount, settled_amount, status
            ) VALUES (
                #{id}, #{requestId}, #{userId}, #{apiKeyId}, #{modelId}, #{groupId},
                #{pricingVersionId}, #{matchedRuleId}, #{contextTierId},
                #{engineMode}, #{billingType}, #{baseUnitPrice}, #{effectiveUnitPrice},
                #{inputTokenRatio}, #{outputTokenRatio}, #{cachedInputTokenRatio},
                #{cacheWrite5mTokenRatio}, #{cacheWrite1hTokenRatio},
                #{audioInputTokenRatio}, #{audioOutputTokenRatio},
                #{groupMultiplier}, #{timeRuleId}, #{timeRuleName}, #{timeMultiplier}, #{pricingTime},
                #{baseUsageAmount}, CAST(#{usageSnapshotJson} AS jsonb), CAST(#{calculationSnapshotJson} AS jsonb),
                #{legacyAmount}, #{calculatedAmount}, #{settledAmount}, #{status}
            )
            ON CONFLICT (request_id) DO NOTHING
            """)
    int insertBillingDetail(GatewayBillingDetail row);

    @Insert("""
            INSERT INTO request_logs (
                id, request_id, user_id, api_key_id, model_id, supplier_id, channel_id, channel_model_id, group_id,
                public_model, upstream_model, started_at, completed_at, duration_ms,
                status_code, platform_error_code, input_tokens, output_tokens, cached_tokens,
                billed_amount, price_multiplier, supplier_input_price, supplier_cached_input_price,
                supplier_output_price, supplier_cost_amount, supplier_cost_currency,
                gross_margin_amount, supplier_error_category, streaming, retry_count, client_ip,
                user_agent_hash, upstream_error_summary, route_switch_reason, metadata
                , request_summary, response_summary, request_detail, response_detail, request_payload_size, response_payload_size
            ) VALUES (
                #{id}, #{requestId}, #{userId}, #{apiKeyId}, #{modelId}, #{supplierId},
                #{channelId}, #{channelModelId}, #{groupId},
                #{publicModel}, #{upstreamModel}, #{startedAt}, #{completedAt}, #{durationMs},
                #{statusCode}, #{platformErrorCode}, #{inputTokens}, #{outputTokens}, #{cachedTokens},
                #{billedAmount}, #{priceMultiplier}, #{supplierInputPrice}, #{supplierCachedInputPrice},
                #{supplierOutputPrice}, #{supplierCostAmount}, #{supplierCostCurrency},
                #{grossMarginAmount}, #{supplierErrorCategory}, #{streaming}, #{retryCount}, CAST(#{clientIp} AS inet),
                #{userAgentHash}, #{upstreamErrorSummary}, #{routeSwitchReason}, '{}'::jsonb,
                CAST(#{requestSummaryJson} AS jsonb), CAST(#{responseSummaryJson} AS jsonb),
                CAST(#{requestDetailJson} AS jsonb), CAST(#{responseDetailJson} AS jsonb),
                #{requestPayloadSize}, #{responsePayloadSize}
            )
            """)
    int insert(GatewayRequestLog row);

    /** 异步观测写入的批量入口，减少高并发下每条日志一次数据库往返。 */
    @Insert("""
            <script>
            INSERT INTO request_logs (
                id, request_id, user_id, api_key_id, model_id, supplier_id, channel_id, channel_model_id, group_id,
                public_model, upstream_model, started_at, completed_at, duration_ms,
                status_code, platform_error_code, input_tokens, output_tokens, cached_tokens,
                billed_amount, price_multiplier, supplier_input_price, supplier_cached_input_price,
                supplier_output_price, supplier_cost_amount, supplier_cost_currency,
                gross_margin_amount, supplier_error_category, streaming, retry_count, client_ip,
                user_agent_hash, upstream_error_summary, route_switch_reason, metadata
                , request_summary, response_summary, request_detail, response_detail, request_payload_size, response_payload_size
            ) VALUES
            <foreach collection="rows" item="row" separator=",">
                (
                    #{row.id}, #{row.requestId}, #{row.userId}, #{row.apiKeyId}, #{row.modelId}, #{row.supplierId},
                    #{row.channelId}, #{row.channelModelId}, #{row.groupId}, #{row.publicModel}, #{row.upstreamModel},
                    #{row.startedAt}, #{row.completedAt}, #{row.durationMs}, #{row.statusCode}, #{row.platformErrorCode},
                    #{row.inputTokens}, #{row.outputTokens}, #{row.cachedTokens}, #{row.billedAmount}, #{row.priceMultiplier},
                    #{row.supplierInputPrice}, #{row.supplierCachedInputPrice}, #{row.supplierOutputPrice},
                    #{row.supplierCostAmount}, #{row.supplierCostCurrency}, #{row.grossMarginAmount},
                    #{row.supplierErrorCategory}, #{row.streaming}, #{row.retryCount}, CAST(#{row.clientIp} AS inet),
                    #{row.userAgentHash}, #{row.upstreamErrorSummary}, #{row.routeSwitchReason}, '{}'::jsonb,
                    CAST(#{row.requestSummaryJson} AS jsonb), CAST(#{row.responseSummaryJson} AS jsonb),
                    CAST(#{row.requestDetailJson} AS jsonb), CAST(#{row.responseDetailJson} AS jsonb),
                    #{row.requestPayloadSize}, #{row.responsePayloadSize}
                )
            </foreach>
            </script>
            """)
    int insertBatch(@Param("rows") List<GatewayRequestLog> rows);

    @Insert("""
            INSERT INTO upstream_attempt_logs (
                id, request_id, supplier_id, channel_id, channel_model_id, model_id,
                attempt_no, started_at, completed_at, duration_ms, outcome,
                upstream_status, error_category, error_summary
            ) VALUES (
                #{id}, #{requestId}, #{supplierId}, #{channelId}, #{channelModelId}, #{modelId},
                #{attemptNo}, #{startedAt}, #{completedAt}, #{durationMs}, #{outcome},
                #{upstreamStatus}, #{errorCategory}, #{errorSummary}
            )
            """)
    int insertAttempt(GatewayUpstreamAttemptLog row);

    /** 上游尝试日志批量写入，失败时由 Service 回退逐条写入。 */
    @Insert("""
            <script>
            INSERT INTO upstream_attempt_logs (
                id, request_id, supplier_id, channel_id, channel_model_id, model_id,
                attempt_no, started_at, completed_at, duration_ms, outcome,
                upstream_status, error_category, error_summary
            ) VALUES
            <foreach collection="rows" item="row" separator=",">
                (
                    #{row.id}, #{row.requestId}, #{row.supplierId}, #{row.channelId}, #{row.channelModelId}, #{row.modelId},
                    #{row.attemptNo}, #{row.startedAt}, #{row.completedAt}, #{row.durationMs}, #{row.outcome},
                    #{row.upstreamStatus}, #{row.errorCategory}, #{row.errorSummary}
                )
            </foreach>
            </script>
            """)
    int insertAttemptBatch(@Param("rows") List<GatewayUpstreamAttemptLog> rows);
}
