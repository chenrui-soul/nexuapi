package com.nexusapi.server.modules.model.infrastructure.persistence;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 模型市场读取公开模型目录；指定分组时仅追加分组归属和倍率，不检查运行时路由。 */
@Mapper
public interface ModelMarketMapper {

    @Select("""
            SELECT m.id, m.public_name, m.display_name, m.provider, m.capability_type,
                   m.context_window, m.max_output_tokens, m.supports_streaming, m.supports_tools,
                   m.supports_structured_output,
                   NULL::uuid AS service_group_id, NULL::varchar AS service_group_name,
                   NULL::numeric AS price_multiplier,
                   m.input_price, m.output_price, m.cached_input_price, m.price_unit,
                   m.billing_type, m.unit_price, m.display_original_price,
                   m.input_token_ratio, m.output_token_ratio,
                   m.audio_input_token_ratio, m.audio_output_token_ratio,
                   m.cached_input_token_ratio, m.cache_write_5m_token_ratio, m.cache_write_1h_token_ratio,
                   m.charge_desc, m.active_pricing_version_id,
                   NULL::uuid AS recommended_service_group_id,
                   NULL::varchar AS recommended_service_group_name,
                   NULL::numeric AS recommended_price_multiplier
              FROM ai_models m
             WHERE m.id = #{modelId}
               AND m.status = 'active'
               AND m.public_visible = true
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "public_name", javaType = String.class),
            @Arg(column = "display_name", javaType = String.class),
            @Arg(column = "provider", javaType = String.class),
            @Arg(column = "capability_type", javaType = String.class),
            @Arg(column = "context_window", javaType = Long.class),
            @Arg(column = "max_output_tokens", javaType = Long.class),
            @Arg(column = "supports_streaming", javaType = boolean.class),
            @Arg(column = "supports_tools", javaType = boolean.class),
            @Arg(column = "supports_structured_output", javaType = boolean.class),
            @Arg(column = "service_group_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "service_group_name", javaType = String.class),
            @Arg(column = "price_multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "input_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "output_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "cached_input_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "price_unit", javaType = String.class),
            @Arg(column = "billing_type", javaType = int.class),
            @Arg(column = "unit_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "display_original_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "input_token_ratio", javaType = long.class),
            @Arg(column = "output_token_ratio", javaType = long.class),
            @Arg(column = "audio_input_token_ratio", javaType = long.class),
            @Arg(column = "audio_output_token_ratio", javaType = long.class),
            @Arg(column = "cached_input_token_ratio", javaType = long.class),
            @Arg(column = "cache_write_5m_token_ratio", javaType = long.class),
            @Arg(column = "cache_write_1h_token_ratio", javaType = long.class),
            @Arg(column = "charge_desc", javaType = String.class),
            @Arg(column = "active_pricing_version_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "recommended_service_group_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "recommended_service_group_name", javaType = String.class),
            @Arg(column = "recommended_price_multiplier", javaType = java.math.BigDecimal.class)
    })
    ModelMarketRow findById(@Param("modelId") UUID modelId);

    @Select("""
            SELECT api.id, api.interface_code, api.interface_name, api.interface_version,
                   api.capability_type, api.transport_mode, api.http_method, api.public_path,
                   api.request_content_type, api.description,
                   api.request_schema::text AS request_schema_json,
                   api.response_schema::text AS response_schema_json
              FROM model_interfaces relation
              JOIN api_interfaces api ON api.id = relation.interface_id
             WHERE relation.model_id = #{modelId}
               AND api.status = 'active'
             ORDER BY api.interface_name, api.id
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "interface_code", javaType = String.class),
            @Arg(column = "interface_name", javaType = String.class),
            @Arg(column = "interface_version", javaType = String.class),
            @Arg(column = "capability_type", javaType = String.class),
            @Arg(column = "transport_mode", javaType = String.class),
            @Arg(column = "http_method", javaType = String.class),
            @Arg(column = "public_path", javaType = String.class),
            @Arg(column = "request_content_type", javaType = String.class),
            @Arg(column = "description", javaType = String.class),
            @Arg(column = "request_schema_json", javaType = String.class),
            @Arg(column = "response_schema_json", javaType = String.class)
    })
    List<ModelMarketInterfaceRow> findInterfaces(@Param("modelId") UUID modelId);

    @Select("""
            <script>
            SELECT g.id, g.code, g.name, g.description, g.price_multiplier
              FROM routing_group_models relation
              JOIN routing_groups g ON g.id = relation.group_id
             WHERE relation.model_id = #{modelId}
               AND relation.source_status = 'active'
               AND g.status = 'active'
               AND (
                    g.audience = 'all'
                    <if test="userId != null">
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
                    </if>
               )
             ORDER BY g.price_multiplier, g.name, g.id
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "code", javaType = String.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "description", javaType = String.class),
            @Arg(column = "price_multiplier", javaType = java.math.BigDecimal.class)
    })
    List<ModelMarketGroupRow> findGroupPrices(@Param("modelId") UUID modelId, @Param("userId") UUID userId);

    @Select("""
            <script>
            SELECT m.id, m.public_name, m.display_name, m.provider, m.capability_type,
                   m.context_window, m.max_output_tokens, m.supports_streaming, m.supports_tools,
                   m.supports_structured_output,
                   <choose>
                     <when test="serviceGroupId != null">
                       g.id AS service_group_id, g.name AS service_group_name, g.price_multiplier,
                     </when>
                     <otherwise>
                       NULL::uuid AS service_group_id, NULL::varchar AS service_group_name,
                       NULL::numeric AS price_multiplier,
                     </otherwise>
                   </choose>
                   m.input_price, m.output_price, m.cached_input_price, m.price_unit,
                   m.billing_type, m.unit_price, m.display_original_price,
                   m.input_token_ratio, m.output_token_ratio,
                   m.audio_input_token_ratio, m.audio_output_token_ratio,
                   m.cached_input_token_ratio, m.cache_write_5m_token_ratio, m.cache_write_1h_token_ratio,
                   m.charge_desc, m.active_pricing_version_id,
                   recommendation.service_group_id AS recommended_service_group_id,
                   recommendation.service_group_name AS recommended_service_group_name,
                   recommendation.price_multiplier AS recommended_price_multiplier
              FROM ai_models m
              <if test="serviceGroupId != null">
              JOIN routing_group_models rgm
                ON rgm.model_id = m.id AND rgm.group_id = #{serviceGroupId} AND rgm.source_status = 'active'
              JOIN routing_groups g
                ON g.id = rgm.group_id AND g.status = 'active'
              </if>
              LEFT JOIN LATERAL (
                SELECT candidate.id AS service_group_id,
                       candidate.name AS service_group_name,
                       candidate.price_multiplier
                  FROM routing_group_models candidate_relation
                  JOIN routing_groups candidate
                    ON candidate.id = candidate_relation.group_id
                   AND candidate.status = 'active'
                 WHERE candidate_relation.model_id = m.id
                   AND candidate_relation.source_status = 'active'
                   AND (
                        candidate.audience = 'all'
                        OR (candidate.audience = 'assigned' AND EXISTS (
                            SELECT 1
                              FROM routing_group_user_grants grant_row
                              JOIN users authorized_user ON authorized_user.id = grant_row.user_id
                             WHERE grant_row.group_id = candidate.id
                               AND grant_row.user_id = #{userId}
                               AND grant_row.status = 'active'
                               AND (grant_row.expires_at IS NULL OR grant_row.expires_at > now())
                               AND authorized_user.status = 'active'
                               AND authorized_user.deleted_at IS NULL
                        ))
                   )
                 ORDER BY
                   (CASE WHEN m.active_pricing_version_id IS NULL THEN m.input_price ELSE m.unit_price END)
                   * candidate.price_multiplier,
                   candidate.price_multiplier, candidate.name, candidate.id
                 LIMIT 1
              ) recommendation ON TRUE
             WHERE m.status = 'active'
               AND m.public_visible = true
               <if test="allowedModelIds != null and !allowedModelIds.isEmpty()">
                 AND m.id IN
                 <foreach collection="allowedModelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               </if>
               <if test="query != null and query != ''">
                 AND (lower(m.public_name) LIKE concat('%', lower(#{query}), '%')
                      OR lower(m.display_name) LIKE concat('%', lower(#{query}), '%'))
               </if>
               <if test="provider != null and provider != ''">
                 AND lower(m.provider) = lower(#{provider})
               </if>
               <if test="capabilityType != null and capabilityType != ''">
                 AND m.capability_type = #{capabilityType}
               </if>
               <if test="supportsStreaming != null">
                 AND m.supports_streaming = #{supportsStreaming}
               </if>
               <if test="supportsTools != null">
                 AND m.supports_tools = #{supportsTools}
               </if>
             ORDER BY
               <choose>
                 <when test="sort == 'price_asc' and serviceGroupId != null">CASE WHEN m.active_pricing_version_id IS NULL THEN m.input_price ELSE m.unit_price END * g.price_multiplier, m.public_name</when>
                 <when test="sort == 'price_desc' and serviceGroupId != null">CASE WHEN m.active_pricing_version_id IS NULL THEN m.input_price ELSE m.unit_price END * g.price_multiplier DESC, m.public_name</when>
                 <when test="sort == 'price_asc'">CASE WHEN m.active_pricing_version_id IS NULL THEN m.input_price ELSE m.unit_price END, m.public_name</when>
                 <when test="sort == 'price_desc'">CASE WHEN m.active_pricing_version_id IS NULL THEN m.input_price ELSE m.unit_price END DESC, m.public_name</when>
                 <otherwise>m.public_name</otherwise>
               </choose>
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "public_name", javaType = String.class),
            @Arg(column = "display_name", javaType = String.class),
            @Arg(column = "provider", javaType = String.class),
            @Arg(column = "capability_type", javaType = String.class),
            @Arg(column = "context_window", javaType = Long.class),
            @Arg(column = "max_output_tokens", javaType = Long.class),
            @Arg(column = "supports_streaming", javaType = boolean.class),
            @Arg(column = "supports_tools", javaType = boolean.class),
            @Arg(column = "supports_structured_output", javaType = boolean.class),
            @Arg(column = "service_group_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "service_group_name", javaType = String.class),
            @Arg(column = "price_multiplier", javaType = java.math.BigDecimal.class),
            @Arg(column = "input_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "output_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "cached_input_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "price_unit", javaType = String.class),
            @Arg(column = "billing_type", javaType = int.class),
            @Arg(column = "unit_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "display_original_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "input_token_ratio", javaType = long.class),
            @Arg(column = "output_token_ratio", javaType = long.class),
            @Arg(column = "audio_input_token_ratio", javaType = long.class),
            @Arg(column = "audio_output_token_ratio", javaType = long.class),
            @Arg(column = "cached_input_token_ratio", javaType = long.class),
            @Arg(column = "cache_write_5m_token_ratio", javaType = long.class),
            @Arg(column = "cache_write_1h_token_ratio", javaType = long.class),
            @Arg(column = "charge_desc", javaType = String.class),
            @Arg(column = "active_pricing_version_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "recommended_service_group_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "recommended_service_group_name", javaType = String.class),
            @Arg(column = "recommended_price_multiplier", javaType = java.math.BigDecimal.class)
    })
    List<ModelMarketRow> findPage(
            @Param("serviceGroupId") UUID serviceGroupId,
            @Param("userId") UUID userId,
            @Param("allowedModelIds") List<UUID> allowedModelIds,
            @Param("query") String query,
            @Param("provider") String provider,
            @Param("capabilityType") String capabilityType,
            @Param("supportsStreaming") Boolean supportsStreaming,
            @Param("supportsTools") Boolean supportsTools,
            @Param("sort") String sort,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(DISTINCT m.id)
              FROM ai_models m
              <if test="serviceGroupId != null">
              JOIN routing_group_models rgm
                ON rgm.model_id = m.id AND rgm.group_id = #{serviceGroupId} AND rgm.source_status = 'active'
              JOIN routing_groups g
                ON g.id = rgm.group_id AND g.status = 'active'
              </if>
             WHERE m.status = 'active' AND m.public_visible = true
               <if test="allowedModelIds != null and !allowedModelIds.isEmpty()">
                 AND m.id IN
                 <foreach collection="allowedModelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               </if>
               <if test="query != null and query != ''">
                 AND (lower(m.public_name) LIKE concat('%', lower(#{query}), '%')
                      OR lower(m.display_name) LIKE concat('%', lower(#{query}), '%'))
               </if>
               <if test="provider != null and provider != ''">AND lower(m.provider) = lower(#{provider})</if>
               <if test="capabilityType != null and capabilityType != ''">AND m.capability_type = #{capabilityType}</if>
               <if test="supportsStreaming != null">AND m.supports_streaming = #{supportsStreaming}</if>
               <if test="supportsTools != null">AND m.supports_tools = #{supportsTools}</if>
            </script>
            """)
    long count(
            @Param("serviceGroupId") UUID serviceGroupId,
            @Param("allowedModelIds") List<UUID> allowedModelIds,
            @Param("query") String query,
            @Param("provider") String provider,
            @Param("capabilityType") String capabilityType,
            @Param("supportsStreaming") Boolean supportsStreaming,
            @Param("supportsTools") Boolean supportsTools
    );
}
