package com.nexusapi.server.modules.admin.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.admin.entity.AdminRequestLogRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 管理员调用日志查询；只选择排障所需公开字段与已脱敏载荷。 */
@Mapper
public interface AdminRequestLogMapper {
    @Select("""
            <script>
            SELECT r.id, r.request_id, r.user_id, u.display_name AS user_display_name,
                   k.name AS api_key_name, g.name AS service_group_name,
                   s.code AS supplier_code, s.name AS supplier_name,
                   r.started_at, r.completed_at, r.duration_ms, r.public_model,
                   r.status_code, r.input_tokens, r.output_tokens, r.cached_tokens,
                   r.billed_amount, r.streaming, r.retry_count, r.platform_error_code,
                   COALESCE(r.request_summary, '{}'::jsonb)::text AS request_summary_json,
                   COALESCE(r.response_summary, '{}'::jsonb)::text AS response_summary_json,
                   COALESCE(r.request_detail, '{}'::jsonb)::text AS request_detail_json,
                   COALESCE(r.response_detail, '{}'::jsonb)::text AS response_detail_json,
                   r.request_payload_size, r.response_payload_size
              FROM request_logs r
              LEFT JOIN users u ON u.id = r.user_id
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
              LEFT JOIN suppliers s ON s.id = r.supplier_id
             WHERE 1 = 1
               <if test="query != null and query != ''">
                 AND (r.request_id ILIKE '%' || #{query} || '%'
                      OR r.public_model ILIKE '%' || #{query} || '%'
                      OR COALESCE(u.display_name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(k.name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(g.name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(s.name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(s.code, '') ILIKE '%' || #{query} || '%')
               </if>
               <if test="status == 'success'">AND r.status_code BETWEEN 200 AND 299</if>
               <if test="status == 'failed'">AND (r.status_code IS NULL OR r.status_code NOT BETWEEN 200 AND 299)</if>
               <if test="model != null and model != ''">AND r.public_model = #{model}</if>
               <if test="period == '24h'">AND r.created_at &gt;= now() - interval '24 hours'</if>
               <if test="period == '7d'">AND r.created_at &gt;= now() - interval '7 days'</if>
               <if test="period == '30d'">AND r.created_at &gt;= now() - interval '30 days'</if>
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @Results(id = "adminRequestLogRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "request_id", property = "requestId"),
            @Result(column = "user_id", property = "userId", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "user_display_name", property = "userDisplayName"),
            @Result(column = "api_key_name", property = "apiKeyName"),
            @Result(column = "service_group_name", property = "serviceGroupName"),
            @Result(column = "supplier_code", property = "supplierCode"),
            @Result(column = "supplier_name", property = "supplierName"),
            @Result(column = "started_at", property = "startedAt"),
            @Result(column = "completed_at", property = "completedAt"),
            @Result(column = "duration_ms", property = "durationMs"),
            @Result(column = "public_model", property = "publicModel"),
            @Result(column = "status_code", property = "statusCode"),
            @Result(column = "input_tokens", property = "inputTokens"),
            @Result(column = "output_tokens", property = "outputTokens"),
            @Result(column = "cached_tokens", property = "cachedTokens"),
            @Result(column = "billed_amount", property = "billedAmount"),
            @Result(column = "streaming", property = "streaming"),
            @Result(column = "retry_count", property = "retryCount"),
            @Result(column = "platform_error_code", property = "platformErrorCode"),
            @Result(column = "request_summary_json", property = "requestSummaryJson"),
            @Result(column = "response_summary_json", property = "responseSummaryJson"),
            @Result(column = "request_detail_json", property = "requestDetailJson"),
            @Result(column = "response_detail_json", property = "responseDetailJson"),
            @Result(column = "request_payload_size", property = "requestPayloadSize"),
            @Result(column = "response_payload_size", property = "responsePayloadSize")
    })
    List<AdminRequestLogRow> findPage(@Param("query") String query, @Param("status") String status,
                                      @Param("model") String model, @Param("period") String period,
                                      @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*)
              FROM request_logs r
              LEFT JOIN users u ON u.id = r.user_id
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
              LEFT JOIN suppliers s ON s.id = r.supplier_id
             WHERE 1 = 1
               <if test="query != null and query != ''">
                 AND (r.request_id ILIKE '%' || #{query} || '%' OR r.public_model ILIKE '%' || #{query} || '%'
                      OR COALESCE(u.display_name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(k.name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(g.name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(s.name, '') ILIKE '%' || #{query} || '%'
                      OR COALESCE(s.code, '') ILIKE '%' || #{query} || '%')
               </if>
               <if test="status == 'success'">AND r.status_code BETWEEN 200 AND 299</if>
               <if test="status == 'failed'">AND (r.status_code IS NULL OR r.status_code NOT BETWEEN 200 AND 299)</if>
               <if test="model != null and model != ''">AND r.public_model = #{model}</if>
               <if test="period == '24h'">AND r.created_at &gt;= now() - interval '24 hours'</if>
               <if test="period == '7d'">AND r.created_at &gt;= now() - interval '7 days'</if>
               <if test="period == '30d'">AND r.created_at &gt;= now() - interval '30 days'</if>
            </script>
            """)
    long countLogs(@Param("query") String query, @Param("status") String status,
                   @Param("model") String model, @Param("period") String period);

    @Select("""
            SELECT r.id, r.request_id, r.user_id, u.display_name AS user_display_name,
                   k.name AS api_key_name, g.name AS service_group_name,
                   s.code AS supplier_code, s.name AS supplier_name,
                   r.started_at, r.completed_at, r.duration_ms, r.public_model,
                   r.status_code, r.input_tokens, r.output_tokens, r.cached_tokens,
                   r.billed_amount, r.streaming, r.retry_count, r.platform_error_code,
                   COALESCE(r.request_summary, '{}'::jsonb)::text AS request_summary_json,
                   COALESCE(r.response_summary, '{}'::jsonb)::text AS response_summary_json,
                   COALESCE(r.request_detail, '{}'::jsonb)::text AS request_detail_json,
                   COALESCE(r.response_detail, '{}'::jsonb)::text AS response_detail_json,
                   r.request_payload_size, r.response_payload_size
              FROM request_logs r
              LEFT JOIN users u ON u.id = r.user_id
              LEFT JOIN api_keys k ON k.id = r.api_key_id AND k.user_id = r.user_id
              LEFT JOIN routing_groups g ON g.id = r.group_id
              LEFT JOIN suppliers s ON s.id = r.supplier_id
             WHERE r.request_id = #{requestId}
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT 1
            """)
    @org.apache.ibatis.annotations.ResultMap("adminRequestLogRow")
    AdminRequestLogRow findByRequestId(@Param("requestId") String requestId);
}
