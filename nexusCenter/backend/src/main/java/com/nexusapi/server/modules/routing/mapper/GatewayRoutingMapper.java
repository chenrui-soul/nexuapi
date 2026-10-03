package com.nexusapi.server.modules.routing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.routing.model.RuntimeGroupRow;
import com.nexusapi.server.modules.routing.model.RuntimeContextTierRow;
import com.nexusapi.server.modules.routing.model.RuntimeModelRow;
import com.nexusapi.server.modules.routing.model.RuntimePricingRuleRow;
import com.nexusapi.server.modules.routing.model.RuntimeRouteRow;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/**
 * Gateway 运行时只读数据访问层。
 *
 * <p>所有可用性条件都在 SQL 中 fail closed，避免 Service 忘记过滤停用模型、分组、映射或人工停用渠道。</p>
 */
@Mapper
public interface GatewayRoutingMapper {

    @Select("""
            SELECT id, public_name, provider, capability_type, context_window, max_output_tokens,
                   supports_streaming, input_price, output_price, cached_input_price,
                   price_unit, billing_type, unit_price, display_original_price,
                   input_token_ratio, output_token_ratio, audio_input_token_ratio, audio_output_token_ratio,
                   cached_input_token_ratio,
                   cache_write_5m_token_ratio, cache_write_1h_token_ratio,
                   context_tier_mode, pricing_unmatched_behavior, active_pricing_version_id,
                   created_at
              FROM ai_models
             WHERE public_name = #{publicName}
               AND status = 'active'
             LIMIT 1
            """)
    @Results(id = "runtimeModelRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "public_name", property = "publicName"),
            @Result(column = "capability_type", property = "capabilityType"),
            @Result(column = "context_window", property = "contextWindow"),
            @Result(column = "max_output_tokens", property = "maxOutputTokens"),
            @Result(column = "supports_streaming", property = "supportsStreaming"),
            @Result(column = "input_price", property = "inputPrice"),
            @Result(column = "output_price", property = "outputPrice"),
            @Result(column = "cached_input_price", property = "cachedInputPrice"),
            @Result(column = "price_unit", property = "priceUnit"),
            @Result(column = "billing_type", property = "billingType"),
            @Result(column = "unit_price", property = "unitPrice"),
            @Result(column = "display_original_price", property = "displayOriginalPrice"),
            @Result(column = "input_token_ratio", property = "inputTokenRatio"),
            @Result(column = "output_token_ratio", property = "outputTokenRatio"),
            @Result(column = "audio_input_token_ratio", property = "audioInputTokenRatio"),
            @Result(column = "audio_output_token_ratio", property = "audioOutputTokenRatio"),
            @Result(column = "cached_input_token_ratio", property = "cachedInputTokenRatio"),
            @Result(column = "cache_write_5m_token_ratio", property = "cacheWrite5mTokenRatio"),
            @Result(column = "cache_write_1h_token_ratio", property = "cacheWrite1hTokenRatio"),
            @Result(column = "context_tier_mode", property = "contextTierMode"),
            @Result(column = "pricing_unmatched_behavior", property = "pricingUnmatchedBehavior"),
            @Result(column = "active_pricing_version_id", property = "activePricingVersionId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "created_at", property = "createdAt")
    })
    RuntimeModelRow findModelByPublicName(@Param("publicName") String publicName);

    @Select("""
            <script>
            SELECT DISTINCT m.id, m.public_name, m.provider, m.capability_type, m.context_window, m.max_output_tokens,
                   m.supports_streaming, m.input_price, m.output_price, m.cached_input_price,
                   m.price_unit, m.billing_type, m.unit_price, m.display_original_price,
                   m.input_token_ratio, m.output_token_ratio, m.audio_input_token_ratio, m.audio_output_token_ratio,
                   m.cached_input_token_ratio,
                   m.cache_write_5m_token_ratio, m.cache_write_1h_token_ratio,
                   m.context_tier_mode, m.pricing_unmatched_behavior, m.active_pricing_version_id,
                   m.created_at
              FROM ai_models m
             WHERE m.status = 'active'
               AND m.public_visible = true
               <if test="allowedModelIds != null and !allowedModelIds.isEmpty()">
                 AND m.id IN
                 <foreach collection="allowedModelIds" item="id" open="(" separator="," close=")">#{id}</foreach>
               </if>
               AND EXISTS (
                   SELECT 1
                     FROM routing_group_models rgm
                     JOIN routing_groups g ON g.id = rgm.group_id
                     JOIN routing_group_suppliers rgs ON rgs.group_id = g.id AND rgs.status = 'active'
                     JOIN routing_group_supplier_credentials rgsc
                       ON rgsc.group_id = g.id AND rgsc.supplier_id = rgs.supplier_id
                      AND rgsc.status = 'active' AND rgsc.encrypted_credential IS NOT NULL
                     JOIN suppliers s ON s.id = rgs.supplier_id AND s.status = 'active'
                     JOIN channels c ON c.supplier_id = s.id
                    WHERE rgm.group_id = g.id
                      AND rgm.model_id = m.id
                      AND rgm.source_status = 'active'
                      AND g.status = 'active'
                      AND (
                           c.endpoint_type = 'multimodal'
                           OR c.endpoint_type = m.capability_type
                           OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
                      )
                      AND c.status IN ('active', 'degraded')
                      AND s.health_status != 'unavailable'
                      <choose>
                        <when test="allowedGroupIds != null and !allowedGroupIds.isEmpty()">
                          AND g.id IN
                          <foreach collection="allowedGroupIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                        </when>
                        <otherwise>
                          AND g.audience = 'all'
                        </otherwise>
                      </choose>
               )
             ORDER BY m.public_name
            </script>
            """)
    @ResultMap("runtimeModelRow")
    List<RuntimeModelRow> findAvailableModels(
            @Param("allowedModelIds") List<UUID> allowedModelIds,
            @Param("allowedGroupIds") List<UUID> allowedGroupIds
    );

    @Select("""
            SELECT id, priority, name, match_conditions::text AS match_conditions_json,
                   billing_type, unit_price, price_multiplier
              FROM model_pricing_rules
             WHERE pricing_version_id = #{pricingVersionId}
             ORDER BY priority, id
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "priority", javaType = int.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "match_conditions_json", javaType = String.class),
            @Arg(column = "billing_type", javaType = Integer.class),
            @Arg(column = "unit_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "price_multiplier", javaType = java.math.BigDecimal.class)
    })
    List<RuntimePricingRuleRow> findPricingRules(@Param("pricingVersionId") UUID pricingVersionId);

    @Select("""
            SELECT id, priority, min_input_tokens, max_input_tokens,
                   input_ratio, output_ratio, cached_input_ratio,
                   cache_write_5m_ratio, cache_write_1h_ratio
              FROM model_context_tiers
             WHERE pricing_version_id = #{pricingVersionId}
             ORDER BY min_input_tokens, priority, id
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "priority", javaType = int.class),
            @Arg(column = "min_input_tokens", javaType = long.class),
            @Arg(column = "max_input_tokens", javaType = Long.class),
            @Arg(column = "input_ratio", javaType = long.class),
            @Arg(column = "output_ratio", javaType = long.class),
            @Arg(column = "cached_input_ratio", javaType = long.class),
            @Arg(column = "cache_write_5m_ratio", javaType = long.class),
            @Arg(column = "cache_write_1h_ratio", javaType = long.class)
    })
    List<RuntimeContextTierRow> findContextTiers(@Param("pricingVersionId") UUID pricingVersionId);

    @Select("""
            SELECT id, code, audience, price_multiplier
              FROM routing_groups
             WHERE id = #{id}
               AND status = 'active'
             LIMIT 1
            """)
    @Results(id = "runtimeGroupRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "audience", property = "audience"),
            @Result(column = "price_multiplier", property = "priceMultiplier")
    })
    RuntimeGroupRow findGroupById(@Param("id") UUID id);

    @Select("""
            SELECT id, code, audience, price_multiplier
              FROM routing_groups
             WHERE lower(code) = lower(#{code})
               AND status = 'active'
             LIMIT 1
            """)
    @ResultMap("runtimeGroupRow")
    RuntimeGroupRow findGroupByCode(@Param("code") String code);

    @Select("""
            <script>
            SELECT g.id, g.code, g.audience, g.price_multiplier
              FROM routing_groups g
             WHERE g.status = 'active'
               <choose>
                 <when test="allowedGroupIds != null and !allowedGroupIds.isEmpty()">
                   AND g.id IN
                   <foreach collection="allowedGroupIds" item="id" open="(" separator="," close=")">#{id}</foreach>
                 </when>
                 <otherwise>
                   AND g.audience = 'all'
                 </otherwise>
               </choose>
               AND EXISTS (
                   SELECT 1
                     FROM routing_group_models rgm
                     JOIN ai_models m ON m.id = rgm.model_id AND m.status = 'active'
                     JOIN routing_group_suppliers rgs ON rgs.group_id = g.id AND rgs.status = 'active'
                     JOIN routing_group_supplier_credentials rgsc
                       ON rgsc.group_id = g.id AND rgsc.supplier_id = rgs.supplier_id
                      AND rgsc.status = 'active' AND rgsc.encrypted_credential IS NOT NULL
                     JOIN suppliers s ON s.id = rgs.supplier_id AND s.status = 'active'
                     JOIN channels c ON c.supplier_id = s.id
                    WHERE rgm.group_id = g.id
                      AND rgm.model_id = #{modelId}
                      AND rgm.source_status = 'active'
                      AND (
                           c.endpoint_type = 'multimodal'
                           OR c.endpoint_type = m.capability_type
                           OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
                      )
                      AND c.status IN ('active', 'degraded')
                      AND s.health_status != 'unavailable'
               )
             ORDER BY g.price_multiplier, g.code
             LIMIT 1
            </script>
            """)
    @ResultMap("runtimeGroupRow")
    RuntimeGroupRow findDefaultGroupForModel(
            @Param("modelId") UUID modelId,
            @Param("allowedGroupIds") List<UUID> allowedGroupIds
    );

    @Select("""
            <script>
            SELECT rgs.id AS route_id,
                   s.id AS supplier_id,
                   s.settlement_currency AS supplier_cost_currency,
                   c.id AS channel_id,
                   c.name AS channel_name,
                   c.provider_type,
                   c.base_url,
                   rgsc.encrypted_credential,
                   rgsc.credential_key_version,
                   true AS group_credential_override,
                   c.proxy_url,
                   c.timeout_ms,
                   c.concurrency_limit AS channel_concurrency_limit,
                   coalesce(c.metadata->'upstream_models'->m.id::text->>'upstream_model', m.public_name) AS upstream_model,
                   m.adapter_key AS adapter_key,
                   coalesce(c.metadata->'upstream_models'->m.id::text->'config', '{}'::jsonb)::text AS config_json,
                   coalesce((c.metadata->'upstream_models'->m.id::text->>'cost_input_price')::numeric, 0) AS supplier_input_price,
                   coalesce((c.metadata->'upstream_models'->m.id::text->>'cost_cached_input_price')::numeric, 0) AS supplier_cached_input_price,
                   coalesce((c.metadata->'upstream_models'->m.id::text->>'cost_output_price')::numeric, 0) AS supplier_output_price,
                   (c.priority::bigint + COALESCE(rgs.priority, 0)::bigint
                       + CASE WHEN c.status = 'degraded' THEN 1000000 ELSE 0 END) AS effective_priority,
                    LEAST(
                        9223372036854775807::numeric,
                        c.weight::numeric * COALESCE(rgs.weight, 1)::numeric
                    )::bigint AS effective_weight,
                   true AS retryable
              FROM routing_group_models rgm
              JOIN routing_groups g ON g.id = rgm.group_id
              JOIN ai_models m ON m.id = rgm.model_id
              JOIN routing_group_suppliers rgs ON rgs.group_id = rgm.group_id AND rgs.status = 'active'
              JOIN routing_group_supplier_credentials rgsc
                ON rgsc.group_id = rgm.group_id AND rgsc.supplier_id = rgs.supplier_id
               AND rgsc.status = 'active' AND rgsc.encrypted_credential IS NOT NULL
              JOIN suppliers s ON s.id = rgs.supplier_id
              JOIN channels c ON c.supplier_id = s.id
             WHERE rgm.group_id = #{groupId}
               AND rgm.model_id = #{modelId}
               AND rgm.source_status = 'active'
               AND g.status = 'active'
               AND m.status = 'active'
               AND (
                    c.endpoint_type = 'multimodal'
                    OR c.endpoint_type = m.capability_type
                    OR (m.capability_type = 'multimodal' AND c.endpoint_type = 'text')
               )
               AND c.status IN ('active', 'degraded')
               AND s.status = 'active'
               AND s.health_status != 'unavailable'
               <if test="operationCode != null">
               AND c.operation_code = #{operationCode}
               </if>
             ORDER BY effective_priority, rgs.id, c.id
            </script>
            """)
    @Results(id = "runtimeRouteRow", value = {
            @Result(column = "route_id", property = "routeId", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_id", property = "supplierId", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "supplier_cost_currency", property = "supplierCostCurrency"),
            @Result(column = "channel_id", property = "channelId", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "channel_name", property = "channelName"),
            @Result(column = "provider_type", property = "providerType"),
            @Result(column = "base_url", property = "baseUrl"),
            @Result(column = "encrypted_credential", property = "encryptedCredential"),
            @Result(column = "credential_key_version", property = "credentialKeyVersion"),
            @Result(column = "group_credential_override", property = "groupCredentialOverride"),
            @Result(column = "proxy_url", property = "proxyUrl"),
            @Result(column = "timeout_ms", property = "timeoutMs"),
            @Result(column = "channel_concurrency_limit", property = "channelConcurrencyLimit"),
            @Result(column = "upstream_model", property = "upstreamModel"),
            @Result(column = "adapter_key", property = "adapterKey"),
            @Result(column = "config_json", property = "configJson"),
            @Result(column = "supplier_input_price", property = "supplierInputPrice"),
            @Result(column = "supplier_cached_input_price", property = "supplierCachedInputPrice"),
            @Result(column = "supplier_output_price", property = "supplierOutputPrice"),
            @Result(column = "effective_priority", property = "effectivePriority"),
            @Result(column = "effective_weight", property = "effectiveWeight"),
            @Result(column = "retryable", property = "retryable")
    })
    List<RuntimeRouteRow> findCandidates(
            @Param("groupId") UUID groupId,
            @Param("modelId") UUID modelId,
            @Param("operationCode") String operationCode
    );

    /** 兼容健康检查和旧集成测试：不指定接口时保留旧的能力路由查询。 */
    default List<RuntimeRouteRow> findCandidates(UUID groupId, UUID modelId) {
        return findCandidates(groupId, modelId, null);
    }
}
