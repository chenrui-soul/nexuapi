package com.nexusapi.server.modules.model.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.model.entity.AdminModelRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 模型管理数据访问层，所有外部输入均通过 MyBatis 参数绑定。 */
@Mapper
public interface AdminModelMapper {

    String SERVICE_GROUPS_COLUMN = """
            COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'id', rg.id,
                    'code', rg.code,
                    'name', rg.name,
                    'source_status', rgm.source_status,
                    'upstream_last_status', rgm.upstream_last_status,
                    'upstream_success_rate', rgm.upstream_success_rate
                ) ORDER BY rg.name, rg.id)
                  FROM routing_group_models rgm
                  JOIN routing_groups rg ON rg.id = rgm.group_id
                 WHERE rgm.model_id = ai_models.id
            ), '[]'::jsonb)::text AS service_groups_json
            """;

    /** 模型接口关系只返回接口文档摘要，不参与真实调用能力判断。 */
    String INTERFACES_COLUMN = """
            COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'id', api.id,
                    'interface_code', api.interface_code,
                    'interface_name', api.interface_name,
                    'http_method', api.http_method,
                    'public_path', api.public_path,
                    'status', api.status
                ) ORDER BY api.interface_name, api.id)
                  FROM model_interfaces relation
                  JOIN api_interfaces api ON api.id = relation.interface_id
                 WHERE relation.model_id = ai_models.id
            ), '[]'::jsonb)::text AS interfaces_json
            """;

    String COLUMNS = """
            id, public_name, display_name, provider, capability_type, adapter_key,
            input_modalities::text AS input_modalities_json,
            output_modalities::text AS output_modalities_json,
            context_window, max_output_tokens, supports_streaming, supports_tools,
            supports_structured_output, input_price, output_price, cached_input_price,
            price_unit, billing_type, unit_price, display_original_price,
            input_token_ratio, output_token_ratio, audio_input_token_ratio, audio_output_token_ratio,
            cached_input_token_ratio, cache_write_5m_token_ratio, cache_write_1h_token_ratio,
            charge_desc, active_pricing_version_id,
            public_visible, status, sync_source, source_model_key, source_managed,
            source_last_seen_at, source_synced_at,
            """ + SERVICE_GROUPS_COLUMN + ", " + INTERFACES_COLUMN + ", created_at, updated_at, version";

    String PAGE_QUERY = """
            <script>
            SELECT id, public_name, display_name, provider, capability_type, adapter_key,
                   input_modalities::text AS input_modalities_json,
                   output_modalities::text AS output_modalities_json,
                   context_window, max_output_tokens, supports_streaming, supports_tools,
                   supports_structured_output, input_price, output_price, cached_input_price,
                   price_unit, billing_type, unit_price, display_original_price,
                   input_token_ratio, output_token_ratio, audio_input_token_ratio, audio_output_token_ratio,
                   cached_input_token_ratio, cache_write_5m_token_ratio, cache_write_1h_token_ratio,
                   charge_desc, active_pricing_version_id,
                   public_visible, status, sync_source, source_model_key, source_managed,
                   source_last_seen_at, source_synced_at,
            """ + SERVICE_GROUPS_COLUMN + """
                   ,
            """ + INTERFACES_COLUMN + """
                   , created_at, updated_at, version
              FROM ai_models
             WHERE 1 = 1
               <if test="query != null">
                 AND (public_name ILIKE '%%' || #{query} || '%%'
                      OR display_name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">
                 AND status = #{status}
               </if>
               <if test="capabilityType != null">
                 AND capability_type = #{capabilityType}
               </if>
               <if test="provider != null">
                 AND provider ILIKE '%%' || #{provider} || '%%'
               </if>
               <if test="serviceGroup != null">
                 AND EXISTS (
                       SELECT 1
                         FROM routing_group_models rgm_filter
                         JOIN routing_groups rg_filter ON rg_filter.id = rgm_filter.group_id
                        WHERE rgm_filter.model_id = ai_models.id
                          AND (rg_filter.code ILIKE '%%' || #{serviceGroup} || '%%'
                               OR rg_filter.name ILIKE '%%' || #{serviceGroup} || '%%')
                 )
               </if>
             ORDER BY updated_at DESC, id
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """;

    @Select("SELECT " + COLUMNS + " FROM ai_models WHERE id = #{id}")
    @Results(id = "adminModelRow", value = {
            @Result(column = "id", property = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "public_name", property = "publicName"),
            @Result(column = "display_name", property = "displayName"),
            @Result(column = "capability_type", property = "capabilityType"),
            @Result(column = "adapter_key", property = "adapterKey"),
            @Result(column = "input_modalities_json", property = "inputModalitiesJson"),
            @Result(column = "output_modalities_json", property = "outputModalitiesJson"),
            @Result(column = "context_window", property = "contextWindow"),
            @Result(column = "max_output_tokens", property = "maxOutputTokens"),
            @Result(column = "supports_streaming", property = "supportsStreaming"),
            @Result(column = "supports_tools", property = "supportsTools"),
            @Result(column = "supports_structured_output", property = "supportsStructuredOutput"),
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
            @Result(column = "charge_desc", property = "chargeDesc"),
            @Result(column = "active_pricing_version_id", property = "activePricingVersionId", javaType = UUID.class,
                    jdbcType = JdbcType.OTHER, typeHandler = PostgresUuidTypeHandler.class),
            @Result(column = "public_visible", property = "publicVisible"),
            @Result(column = "sync_source", property = "syncSource"),
            @Result(column = "source_model_key", property = "sourceModelKey"),
            @Result(column = "source_managed", property = "sourceManaged"),
            @Result(column = "source_last_seen_at", property = "sourceLastSeenAt"),
            @Result(column = "source_synced_at", property = "sourceSyncedAt"),
            @Result(column = "service_groups_json", property = "serviceGroupsJson"),
            @Result(column = "interfaces_json", property = "interfacesJson"),
            @Result(column = "created_at", property = "createdAt"),
            @Result(column = "updated_at", property = "updatedAt")
    })
    AdminModelRow findById(@Param("id") UUID id);

    @Select("""
            <script>
            SELECT """ + " " + COLUMNS + """
              FROM ai_models
             WHERE id IN
             <foreach collection="ids" item="id" open="(" separator="," close=")">
               #{id}
             </foreach>
            </script>
            """)
    @ResultMap("adminModelRow")
    List<AdminModelRow> findByIds(@Param("ids") List<UUID> ids);

    @Select(PAGE_QUERY)
    @ResultMap("adminModelRow")
    List<AdminModelRow> findPage(
            @Param("query") String query,
            @Param("status") String status,
            @Param("capabilityType") String capabilityType,
            @Param("provider") String provider,
            @Param("serviceGroup") String serviceGroup,
            @Param("offset") int offset,
            @Param("limit") int limit
    );

    @Select("""
            <script>
            SELECT count(*)
              FROM ai_models
             WHERE 1 = 1
               <if test="query != null">
                 AND (public_name ILIKE '%%' || #{query} || '%%'
                      OR display_name ILIKE '%%' || #{query} || '%%')
               </if>
               <if test="status != null">
                 AND status = #{status}
               </if>
               <if test="capabilityType != null">
                 AND capability_type = #{capabilityType}
               </if>
               <if test="provider != null">
                 AND provider ILIKE '%%' || #{provider} || '%%'
               </if>
               <if test="serviceGroup != null">
                 AND EXISTS (
                       SELECT 1
                         FROM routing_group_models rgm_filter
                         JOIN routing_groups rg_filter ON rg_filter.id = rgm_filter.group_id
                        WHERE rgm_filter.model_id = ai_models.id
                          AND (rg_filter.code ILIKE '%%' || #{serviceGroup} || '%%'
                               OR rg_filter.name ILIKE '%%' || #{serviceGroup} || '%%')
                 )
               </if>
            </script>
            """)
    long count(
            @Param("query") String query,
            @Param("status") String status,
            @Param("capabilityType") String capabilityType,
            @Param("provider") String provider,
            @Param("serviceGroup") String serviceGroup
    );

    @Update("""
            <script>
            UPDATE ai_models
               SET status = #{status},
                   source_managed = false,
                   updated_at = now(),
                   version = version + 1
             WHERE id IN
             <foreach collection="ids" item="id" open="(" separator="," close=")">
               #{id}
             </foreach>
               AND status &lt;&gt; #{status}
            </script>
            """)
    int updateStatusBatch(@Param("ids") List<UUID> ids, @Param("status") String status);

    @Select("""
            <script>
            SELECT count(*)
              FROM ai_models
             WHERE lower(public_name) = lower(#{publicName})
               <if test="excludedId != null">AND id != #{excludedId}</if>
            </script>
            """)
    int countByPublicName(@Param("publicName") String publicName, @Param("excludedId") UUID excludedId);

    @Insert("""
            INSERT INTO ai_models (
                id, public_name, display_name, provider, capability_type, adapter_key,
                input_modalities, output_modalities, context_window, max_output_tokens,
                supports_streaming, supports_tools, supports_structured_output,
                input_price, output_price, cached_input_price, price_unit, billing_type,
                public_visible, status, metadata
            ) VALUES (
                #{id}, #{publicName}, #{displayName}, #{provider}, #{capabilityType}, #{adapterKey},
                                CAST(#{inputModalitiesJson} AS jsonb), CAST(#{outputModalitiesJson} AS jsonb),
                #{contextWindow}, #{maxOutputTokens}, #{supportsStreaming}, #{supportsTools},
                #{supportsStructuredOutput}, #{inputPrice}, #{outputPrice}, #{cachedInputPrice},
                #{priceUnit}, #{billingType}, #{publicVisible}, #{status}, '{}'::jsonb
            )
            """)
    int insert(AdminModelRow row);

    @Update("""
            UPDATE ai_models
               SET public_name = #{publicName},
                   display_name = #{displayName},
                   provider = #{provider},
                   capability_type = #{capabilityType},
                   adapter_key = #{adapterKey},
                   input_modalities = CAST(#{inputModalitiesJson} AS jsonb),
                   output_modalities = CAST(#{outputModalitiesJson} AS jsonb),
                   context_window = #{contextWindow},
                   max_output_tokens = #{maxOutputTokens},
                   supports_streaming = #{supportsStreaming},
                   supports_tools = #{supportsTools},
                   supports_structured_output = #{supportsStructuredOutput},
                   input_price = #{inputPrice},
                   output_price = #{outputPrice},
                   cached_input_price = #{cachedInputPrice},
                   price_unit = #{priceUnit},
                   billing_type = CASE WHEN active_pricing_version_id IS NULL THEN #{billingType} ELSE billing_type END,
                   public_visible = #{publicVisible},
                   status = #{status},
                   source_managed = false,
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{id}
               AND version = #{version}
            """)
    int update(AdminModelRow row);

    /** 校验模型表单提交的接口 ID 都真实存在，避免写入悬空关系。 */
    @Select("""
            <script>
            SELECT count(*) FROM api_interfaces WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int countInterfaces(@Param("ids") List<UUID> ids);

    /** 模型保存时以表单快照为准重建关系，取消勾选即删除对应关联。 */
    @Delete("DELETE FROM model_interfaces WHERE model_id = #{modelId}")
    int deleteInterfaces(@Param("modelId") UUID modelId);

    /** 保存模型与接口文档的多对多关联；该关系只用于管理端和接口文档展示。 */
    @Insert("""
            <script>
            INSERT INTO model_interfaces (model_id, interface_id)
            SELECT #{modelId}, api.id
              FROM api_interfaces api
             WHERE api.id IN
            <foreach collection="interfaceIds" item="interfaceId" open="(" separator="," close=")">
              #{interfaceId}
            </foreach>
            </script>
            """)
    int insertInterfaces(@Param("modelId") UUID modelId, @Param("interfaceIds") List<UUID> interfaceIds);
}
