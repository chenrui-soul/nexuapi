package com.nexusapi.server.modules.model.pricing.mapper;

import com.nexusapi.server.common.persistence.PostgresUuidTypeHandler;
import com.nexusapi.server.modules.model.pricing.entity.ModelContextTierRow;
import com.nexusapi.server.modules.model.pricing.entity.ModelPricingRuleRow;
import com.nexusapi.server.modules.model.pricing.entity.ModelPricingVersionRow;
import com.nexusapi.server.modules.model.pricing.entity.PricingModelStateRow;
import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.type.JdbcType;

import java.util.List;
import java.util.UUID;

/** 模型价格版本数据访问层；所有版本只插入，激活仅更新模型当前指针。 */
@Mapper
public interface AdminModelPricingMapper {

    @Select("""
            SELECT id, capability_type, active_pricing_version_id,
                   pricing_source_managed, pricing_source_hash, pricing_source_synced_at, version
              FROM ai_models
             WHERE id = #{modelId}
             FOR UPDATE
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "capability_type", javaType = String.class),
            @Arg(column = "active_pricing_version_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "pricing_source_managed", javaType = boolean.class),
            @Arg(column = "pricing_source_hash", javaType = String.class),
            @Arg(column = "pricing_source_synced_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    PricingModelStateRow lockModel(@Param("modelId") UUID modelId);

    @Select("""
            SELECT id, capability_type, active_pricing_version_id,
                   pricing_source_managed, pricing_source_hash, pricing_source_synced_at, version
              FROM ai_models
             WHERE id = #{modelId}
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "capability_type", javaType = String.class),
            @Arg(column = "active_pricing_version_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "pricing_source_managed", javaType = boolean.class),
            @Arg(column = "pricing_source_hash", javaType = String.class),
            @Arg(column = "pricing_source_synced_at", javaType = java.time.Instant.class),
            @Arg(column = "version", javaType = long.class)
    })
    PricingModelStateRow findModel(@Param("modelId") UUID modelId);

    @Select("""
            SELECT COALESCE(MAX(version_no), 0) + 1
              FROM model_pricing_versions
             WHERE model_id = #{modelId}
            """)
    long nextVersionNo(@Param("modelId") UUID modelId);

    @Insert("""
            INSERT INTO model_pricing_versions (
                id, model_id, version_no, billing_type, unit_price, display_original_price,
                input_token_ratio, output_token_ratio, audio_input_token_ratio, audio_output_token_ratio,
                cached_input_token_ratio,
                cache_write_5m_token_ratio, cache_write_1h_token_ratio,
                charge_desc, context_tier_mode, unmatched_behavior,
                source_type, source_hash, source_observed_at, change_note, created_by
            ) VALUES (
                #{id}, #{modelId}, #{versionNo}, #{billingType}, #{unitPrice}, #{displayOriginalPrice},
                #{inputTokenRatio}, #{outputTokenRatio}, #{audioInputTokenRatio}, #{audioOutputTokenRatio},
                #{cachedInputTokenRatio},
                #{cacheWrite5mTokenRatio}, #{cacheWrite1hTokenRatio},
                #{chargeDesc}, #{contextTierMode}, #{unmatchedBehavior},
                #{sourceType}, #{sourceHash}, #{sourceObservedAt}, #{changeNote}, #{createdBy}
            )
            """)
    int insertVersion(ModelPricingVersionRow row);

    @Insert("""
            INSERT INTO model_pricing_rules (
                id, pricing_version_id, priority, name, match_conditions,
                billing_type, unit_price, price_multiplier
            ) VALUES (
                #{id}, #{pricingVersionId}, #{priority}, #{name}, CAST(#{matchConditionsJson} AS jsonb),
                #{billingType}, #{unitPrice}, #{priceMultiplier}
            )
            """)
    int insertRule(ModelPricingRuleRow row);

    @Insert("""
            INSERT INTO model_context_tiers (
                id, pricing_version_id, priority, min_input_tokens, max_input_tokens,
                input_ratio, output_ratio, cached_input_ratio, cache_write_5m_ratio, cache_write_1h_ratio
            ) VALUES (
                #{id}, #{pricingVersionId}, #{priority}, #{minInputTokens}, #{maxInputTokens},
                #{inputRatio}, #{outputRatio}, #{cachedInputRatio}, #{cacheWrite5mRatio}, #{cacheWrite1hRatio}
            )
            """)
    int insertContextTier(ModelContextTierRow row);

    /** 把不可变版本复制为模型当前快照，同时使用模型 version 防止并发覆盖。 */
    @Update("""
            UPDATE ai_models m
               SET billing_type = v.billing_type,
                   unit_price = v.unit_price,
                   display_original_price = v.display_original_price,
                   input_token_ratio = v.input_token_ratio,
                   output_token_ratio = v.output_token_ratio,
                   audio_input_token_ratio = v.audio_input_token_ratio,
                   audio_output_token_ratio = v.audio_output_token_ratio,
                   cached_input_token_ratio = v.cached_input_token_ratio,
                   cache_write_5m_token_ratio = v.cache_write_5m_token_ratio,
                   cache_write_1h_token_ratio = v.cache_write_1h_token_ratio,
                   charge_desc = v.charge_desc,
                   context_tier_mode = v.context_tier_mode,
                   pricing_unmatched_behavior = v.unmatched_behavior,
                   active_pricing_version_id = v.id,
                   pricing_source_managed = false,
                   updated_at = now(),
                   version = m.version + 1
              FROM model_pricing_versions v
             WHERE m.id = #{modelId}
               AND v.id = #{versionId}
               AND v.model_id = m.id
               AND m.version = #{modelVersion}
            """)
    int activateVersion(
            @Param("modelId") UUID modelId,
            @Param("versionId") UUID versionId,
            @Param("modelVersion") long modelVersion
    );

    @Select("""
            SELECT count(*)
              FROM request_billing_details details
             WHERE details.pricing_version_id = #{versionId}
                OR details.matched_rule_id IN (
                    SELECT id FROM model_pricing_rules WHERE pricing_version_id = #{versionId}
                )
                OR details.context_tier_id IN (
                    SELECT id FROM model_context_tiers WHERE pricing_version_id = #{versionId}
                )
            """)
    long countBillingReferences(@Param("versionId") UUID versionId);

    /** 删除历史版本前由 Service 校验非激活且未被计费明细引用；规则和上下文分档由 FK 级联删除。 */
    @Delete("""
            DELETE FROM model_pricing_versions
             WHERE id = #{versionId}
               AND model_id = #{modelId}
               AND id <> (SELECT active_pricing_version_id FROM ai_models WHERE id = #{modelId})
            """)
    int deleteVersion(
            @Param("modelId") UUID modelId,
            @Param("versionId") UUID versionId
    );

    @Update("""
            UPDATE ai_models
               SET version = version + 1, updated_at = now()
             WHERE id = #{modelId} AND version = #{modelVersion}
            """)
    int bumpModelVersion(
            @Param("modelId") UUID modelId,
            @Param("modelVersion") long modelVersion
    );

    /** 切回跟随上游时立即激活最近同步版本；后续同步只在哈希变化时再创建新版本。 */
    @Update("""
            UPDATE ai_models m
               SET billing_type = v.billing_type,
                   unit_price = v.unit_price,
                   display_original_price = v.display_original_price,
                   input_token_ratio = v.input_token_ratio,
                   output_token_ratio = v.output_token_ratio,
                   audio_input_token_ratio = v.audio_input_token_ratio,
                   audio_output_token_ratio = v.audio_output_token_ratio,
                   cached_input_token_ratio = v.cached_input_token_ratio,
                   cache_write_5m_token_ratio = v.cache_write_5m_token_ratio,
                   cache_write_1h_token_ratio = v.cache_write_1h_token_ratio,
                   charge_desc = v.charge_desc,
                   context_tier_mode = v.context_tier_mode,
                   pricing_unmatched_behavior = v.unmatched_behavior,
                   active_pricing_version_id = v.id,
                   pricing_source_managed = true,
                   pricing_source_hash = v.source_hash,
                   pricing_source_synced_at = now(),
                   updated_at = now(),
                   version = m.version + 1
              FROM model_pricing_versions v
             WHERE m.id = #{modelId}
               AND v.id = #{versionId}
               AND v.model_id = m.id
               AND v.source_hash IS NOT NULL
               AND m.version = #{modelVersion}
            """)
    int followSourceVersion(
            @Param("modelId") UUID modelId,
            @Param("versionId") UUID versionId,
            @Param("modelVersion") long modelVersion
    );

    /** 尚无上游价格版本时只打开跟随开关，下一轮完整同步负责创建并激活首个版本。 */
    @Update("""
            UPDATE ai_models
               SET pricing_source_managed = true,
                   pricing_source_hash = NULL,
                   pricing_source_synced_at = NULL,
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{modelId} AND version = #{modelVersion}
            """)
    int enableSourceFollowing(
            @Param("modelId") UUID modelId,
            @Param("modelVersion") long modelVersion
    );

    /** 人工模式只关闭未来同步覆盖，不删除或修改任何历史价格版本。 */
    @Update("""
            UPDATE ai_models
               SET pricing_source_managed = false,
                   updated_at = now(),
                   version = version + 1
             WHERE id = #{modelId} AND version = #{modelVersion}
            """)
    int disableSourceFollowing(
            @Param("modelId") UUID modelId,
            @Param("modelVersion") long modelVersion
    );

    String VERSION_COLUMNS = """
            id, model_id, version_no, billing_type, unit_price, display_original_price,
            input_token_ratio, output_token_ratio, audio_input_token_ratio, audio_output_token_ratio,
            cached_input_token_ratio,
            cache_write_5m_token_ratio, cache_write_1h_token_ratio,
            charge_desc, context_tier_mode, unmatched_behavior,
            source_type, source_hash, source_observed_at, change_note, created_by, created_at
            """;

    @Select("SELECT " + VERSION_COLUMNS + " FROM model_pricing_versions WHERE id = #{versionId}")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "model_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "version_no", javaType = long.class),
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
            @Arg(column = "context_tier_mode", javaType = int.class),
            @Arg(column = "unmatched_behavior", javaType = String.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_hash", javaType = String.class),
            @Arg(column = "source_observed_at", javaType = java.time.Instant.class),
            @Arg(column = "change_note", javaType = String.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class)
    })
    ModelPricingVersionRow findVersion(@Param("versionId") UUID versionId);

    @Select("SELECT " + VERSION_COLUMNS + " FROM model_pricing_versions "
            + "WHERE model_id = #{modelId} AND source_type = #{sourceType} AND source_hash IS NOT NULL "
            + "ORDER BY version_no DESC LIMIT 1")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "model_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "version_no", javaType = long.class),
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
            @Arg(column = "context_tier_mode", javaType = int.class),
            @Arg(column = "unmatched_behavior", javaType = String.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_hash", javaType = String.class),
            @Arg(column = "source_observed_at", javaType = java.time.Instant.class),
            @Arg(column = "change_note", javaType = String.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class)
    })
    ModelPricingVersionRow findLatestSourceVersion(
            @Param("modelId") UUID modelId,
            @Param("sourceType") String sourceType
    );

    @Select("SELECT " + VERSION_COLUMNS + " FROM model_pricing_versions "
            + "WHERE model_id = #{modelId} AND source_type = #{sourceType} AND source_hash = #{sourceHash} "
            + "LIMIT 1")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "model_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "version_no", javaType = long.class),
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
            @Arg(column = "context_tier_mode", javaType = int.class),
            @Arg(column = "unmatched_behavior", javaType = String.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_hash", javaType = String.class),
            @Arg(column = "source_observed_at", javaType = java.time.Instant.class),
            @Arg(column = "change_note", javaType = String.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class)
    })
    ModelPricingVersionRow findSourceVersionByHash(
            @Param("modelId") UUID modelId,
            @Param("sourceType") String sourceType,
            @Param("sourceHash") String sourceHash
    );

    @Select("SELECT " + VERSION_COLUMNS + " FROM model_pricing_versions "
            + "WHERE model_id = #{modelId} ORDER BY version_no DESC LIMIT 50")
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "model_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "version_no", javaType = long.class),
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
            @Arg(column = "context_tier_mode", javaType = int.class),
            @Arg(column = "unmatched_behavior", javaType = String.class),
            @Arg(column = "source_type", javaType = String.class),
            @Arg(column = "source_hash", javaType = String.class),
            @Arg(column = "source_observed_at", javaType = java.time.Instant.class),
            @Arg(column = "change_note", javaType = String.class),
            @Arg(column = "created_by", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "created_at", javaType = java.time.Instant.class)
    })
    List<ModelPricingVersionRow> findVersions(@Param("modelId") UUID modelId);

    @Select("""
            SELECT id, pricing_version_id, priority, name,
                   match_conditions::text AS match_conditions_json,
                   billing_type, unit_price, price_multiplier
              FROM model_pricing_rules
             WHERE pricing_version_id = #{versionId}
             ORDER BY priority, id
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "pricing_version_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "priority", javaType = int.class),
            @Arg(column = "name", javaType = String.class),
            @Arg(column = "match_conditions_json", javaType = String.class),
            @Arg(column = "billing_type", javaType = Integer.class),
            @Arg(column = "unit_price", javaType = java.math.BigDecimal.class),
            @Arg(column = "price_multiplier", javaType = java.math.BigDecimal.class)
    })
    List<ModelPricingRuleRow> findRules(@Param("versionId") UUID versionId);

    @Select("""
            SELECT id, pricing_version_id, priority, min_input_tokens, max_input_tokens,
                   input_ratio, output_ratio, cached_input_ratio, cache_write_5m_ratio, cache_write_1h_ratio
              FROM model_context_tiers
             WHERE pricing_version_id = #{versionId}
             ORDER BY min_input_tokens, priority, id
            """)
    @ConstructorArgs({
            @Arg(column = "id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
                    typeHandler = PostgresUuidTypeHandler.class),
            @Arg(column = "pricing_version_id", javaType = UUID.class, jdbcType = JdbcType.OTHER,
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
    List<ModelContextTierRow> findContextTiers(@Param("versionId") UUID versionId);
}
