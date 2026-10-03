package com.nexusapi.server.modules.supplier.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierChannelDetailRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierFinancialSummaryRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierModelDetailRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierResourceSummaryRow;
import com.nexusapi.server.modules.supplier.entity.AdminSupplierRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.time.Instant;
import java.util.UUID;

/** 供应商管理数据访问层，所有外部输入均通过 MyBatis 参数绑定。 */
@Mapper
public interface AdminSupplierMapper {
    @Select("""
            SELECT id, code, name, supplier_type, status, health_status, billing_mode,
                   settlement_currency, disabled_reason, disabled_at, last_health_checked_at,
                   metadata::text AS metadata_json, created_at, updated_at, version
              FROM suppliers
             WHERE id = #{id}
            """)
    @Results(id = "adminSupplierRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_type", property = "supplierType"),
            @Result(column = "health_status", property = "healthStatus"),
            @Result(column = "billing_mode", property = "billingMode"),
            @Result(column = "settlement_currency", property = "settlementCurrency"),
            @Result(column = "disabled_reason", property = "disabledReason"),
            @Result(column = "disabled_at", property = "disabledAt"),
            @Result(column = "last_health_checked_at", property = "lastHealthCheckedAt"),
            @Result(column = "metadata_json", property = "metadataJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    AdminSupplierRow findById(@Param("id") UUID id);

    /** 资源数量只按现有关系聚合，不读取任何渠道凭证字段。 */
    @Select("""
            SELECT count(DISTINCT c.id) AS channel_count,
                   count(DISTINCT c.id) FILTER (
                       WHERE s.status = 'active' AND c.status IN ('active', 'degraded')
                   ) AS available_channel_count,
                   count(DISTINCT m.id) AS model_count,
                   count(DISTINCT m.id) FILTER (
                       WHERE s.status = 'active' AND c.status IN ('active', 'degraded')
                         AND s.health_status != 'unavailable' AND m.status = 'active'
                         AND g.status = 'active' AND gs.status = 'active' AND gm.source_status = 'active'
                         AND credential.status = 'active' AND credential.encrypted_credential IS NOT NULL
                   ) AS active_model_count
              FROM suppliers s
              LEFT JOIN channels c ON c.supplier_id = s.id
              LEFT JOIN routing_group_suppliers gs ON gs.supplier_id = s.id
              LEFT JOIN routing_groups g ON g.id = gs.group_id
              LEFT JOIN routing_group_models gm ON gm.group_id = g.id
              LEFT JOIN routing_group_supplier_credentials credential
                ON credential.group_id = g.id AND credential.supplier_id = s.id
              LEFT JOIN ai_models m ON m.id = gm.model_id
               AND (c.endpoint_type = 'multimodal' OR c.endpoint_type = m.capability_type
                    OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text'))
             WHERE s.id = #{supplierId}
             GROUP BY s.id
            """)
    AdminSupplierResourceSummaryRow findResourceSummary(@Param("supplierId") UUID supplierId);

    /** 最近 30 天资金与稳定性事实分开聚合，避免请求和重试明细互相放大。 */
    @Select("""
            WITH request_summary AS (
                SELECT count(*) AS request_count,
                       count(*) FILTER (
                           WHERE status_code BETWEEN 200 AND 299 AND platform_error_code IS NULL
                       ) AS success_count,
                       coalesce(sum(billed_amount), 0) AS billed_amount,
                       coalesce(sum(supplier_cost_amount), 0) AS supplier_cost_amount,
                       coalesce(sum(gross_margin_amount), 0) AS gross_margin_amount
                  FROM request_logs
                 WHERE supplier_id = #{supplierId}
                   AND created_at >= #{from} AND created_at < #{to}
            ), attempt_summary AS (
                SELECT count(*) AS attempt_count,
                       count(*) FILTER (WHERE outcome = 'success') AS attempt_success_count,
                       count(*) FILTER (WHERE outcome = 'supplier_failure') AS attempt_supplier_failure_count
                  FROM upstream_attempt_logs
                 WHERE supplier_id = #{supplierId}
                   AND created_at >= #{from} AND created_at < #{to}
            )
            SELECT r.*, a.* FROM request_summary r CROSS JOIN attempt_summary a
            """)
    AdminSupplierFinancialSummaryRow findFinancialSummary(
            @Param("supplierId") UUID supplierId,
            @Param("from") Instant from,
            @Param("to") Instant to
    );

    /** 渠道明细固定最多返回 50 条，最近错误只返回归一化分类。 */
    @Select("""
            SELECT c.id, c.name, c.provider_type, c.base_url, c.status,
                   c.consecutive_failures, c.circuit_open_until,
                   (SELECT count(DISTINCT gm.model_id)
                      FROM routing_group_suppliers gs
                      JOIN routing_group_models gm ON gm.group_id = gs.group_id
                      JOIN ai_models m ON m.id = gm.model_id
                     WHERE gs.supplier_id = c.supplier_id
                       AND (c.endpoint_type = 'multimodal' OR c.endpoint_type = m.capability_type
                            OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text'))) AS mapping_count,
                   latest.outcome AS last_attempt_outcome,
                   latest.error_category AS last_error_category,
                   latest.created_at AS last_attempt_at
              FROM channels c
              LEFT JOIN LATERAL (
                  SELECT a.outcome, a.error_category, a.created_at
                    FROM upstream_attempt_logs a
                   WHERE a.supplier_id = #{supplierId} AND a.channel_id = c.id
                   ORDER BY a.created_at DESC, a.id DESC
                   LIMIT 1
              ) latest ON true
             WHERE c.supplier_id = #{supplierId}
             ORDER BY c.updated_at DESC, c.id
             LIMIT #{limit}
            """)
    List<AdminSupplierChannelDetailRow> findDetailChannels(
            @Param("supplierId") UUID supplierId,
            @Param("limit") int limit
    );

    /** 模型目录从服务分组派生；同一渠道和模型跨分组去重，成本保持 NUMERIC 精度。 */
    @Select("""
            SELECT NULL::uuid AS mapping_id, m.id AS model_id, m.public_name, m.display_name,
                   coalesce(c.metadata->'upstream_models'->m.id::text->>'upstream_model', m.public_name) AS upstream_model,
                   c.id AS channel_id, c.name AS channel_name,
                   coalesce((c.metadata->'upstream_models'->m.id::text->>'cost_input_price')::numeric, 0) AS cost_input_price,
                   coalesce((c.metadata->'upstream_models'->m.id::text->>'cost_cached_input_price')::numeric, 0) AS cost_cached_input_price,
                   coalesce((c.metadata->'upstream_models'->m.id::text->>'cost_output_price')::numeric, 0) AS cost_output_price,
                   CASE WHEN bool_or(g.status = 'active' AND gs.status = 'active' AND gm.source_status = 'active'
                       AND credential.status = 'active' AND credential.encrypted_credential IS NOT NULL)
                       AND c.status IN ('active', 'degraded') AND m.status = 'active'
                       AND s.status = 'active' AND s.health_status != 'unavailable'
                       THEN 'active' ELSE 'disabled' END AS status,
                   greatest(c.updated_at, m.updated_at, max(gm.updated_at)) AS updated_at
              FROM routing_group_suppliers gs
              JOIN routing_groups g ON g.id = gs.group_id
              JOIN routing_group_models gm ON gm.group_id = g.id
              JOIN ai_models m ON m.id = gm.model_id
              JOIN suppliers s ON s.id = gs.supplier_id
              JOIN channels c ON c.supplier_id = s.id
               AND (c.endpoint_type = 'multimodal' OR c.endpoint_type = m.capability_type
                    OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text'))
              LEFT JOIN routing_group_supplier_credentials credential
                ON credential.group_id = g.id AND credential.supplier_id = s.id
             WHERE c.supplier_id = #{supplierId}
             GROUP BY c.id, m.id, s.id
             ORDER BY status, updated_at DESC, c.id, m.id
             LIMIT #{limit}
            """)
    List<AdminSupplierModelDetailRow> findDetailModels(
            @Param("supplierId") UUID supplierId,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT id, code, name, supplier_type, status, health_status, billing_mode,
                   settlement_currency, disabled_reason, disabled_at, last_health_checked_at,
                   metadata::text AS metadata_json, created_at, updated_at, version
              FROM suppliers
             WHERE 1 = 1
               <if test="query != null">
                 AND (code ILIKE '%%' || #{query} || '%%' OR name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">AND status = #{status}</if>
               <if test="healthStatus != null">AND health_status = #{healthStatus}</if>
             ORDER BY updated_at DESC, id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ResultMap("adminSupplierRow")
    List<AdminSupplierRow> findPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("healthStatus") String healthStatus,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*) FROM suppliers
             WHERE 1 = 1
               <if test="query != null">
                 AND (code ILIKE '%%' || #{query} || '%%' OR name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">AND status = #{status}</if>
               <if test="healthStatus != null">AND health_status = #{healthStatus}</if>
            </script>
            """)
    long countPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("healthStatus") String healthStatus
    );

    @Select("""
            <script>
            SELECT count(*) FROM suppliers
             WHERE (lower(code) = lower(#{code}) OR lower(name) = lower(#{name}))
               <if test="excludedId != null">AND id != #{excludedId}</if>
            </script>
            """)
    int countIdentity(
            @Param("code") String code,
            @Param("name") String name,
            @Param("excludedId") UUID excludedId
    );

    @Insert("""
            INSERT INTO suppliers (
                id, code, name, supplier_type, status, health_status, billing_mode,
                settlement_currency, disabled_reason, disabled_at, metadata
            ) VALUES (
                #{id}, #{code}, #{name}, #{supplierType}, #{status}, 'unconfigured', #{billingMode},
                #{settlementCurrency}, #{disabledReason},
                CASE WHEN #{status} = 'active' THEN NULL ELSE now() END,
                CAST(#{metadataJson} AS jsonb)
            )
            """)
    int insert(AdminSupplierRow row);

    @Update("""
            UPDATE suppliers
               SET code = #{code}, name = #{name}, supplier_type = #{supplierType},
                   status = #{status}, billing_mode = #{billingMode},
                   settlement_currency = #{settlementCurrency}, disabled_reason = #{disabledReason},
                   disabled_at = CASE
                       WHEN #{status} = 'active' THEN NULL
                       WHEN status = 'active' OR disabled_at IS NULL THEN now()
                       ELSE disabled_at
                   END,
                   metadata = CAST(#{metadataJson} AS jsonb),
                   updated_at = now(), version = version + 1
             WHERE id = #{id} AND version = #{version}
            """)
    int update(AdminSupplierRow row);
}
